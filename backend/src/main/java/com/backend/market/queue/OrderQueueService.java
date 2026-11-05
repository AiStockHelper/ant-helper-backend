package com.backend.market.queue;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.backend.common.exception.ApiException;
import com.backend.common.properties.AppProperties;
import com.backend.domains.member.domain.MemberAccount;
import com.backend.domains.trade.DailyOrderCap;
import com.backend.domains.trade.TradePlanner.Plan;
import com.backend.market.TradingService;
import com.backend.market.dto.MarketDtos.OrderHistoryItem;
import com.backend.market.dto.MarketDtos.OrderOutcome;
import com.backend.market.dto.MarketDtos.OrderRequest;
import com.backend.order.kis.kis_client.exception.KisClientException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 모든 매수·매도 주문의 입구. 주문을 원장(kis_order_request)에 먼저 적재한 뒤 KIS 호출 줄(KisLane)로 보낸다.
 *
 * <ul>
 *   <li>성공하면 SUCCESS 로 기록한다.</li>
 *   <li>일시적인 오류(호출 한도·토큰·네트워크·KIS 서버 오류)는 RETRY_WAIT 로 두고 스케줄러가 최대
 *       {@link KisLimits#ORDER_MAX_RETRIES} 번 다시 보낸다. 응답을 못 받아 처리 여부가 불확실한 경우에는 다시 보내기 전에
 *       주문 내역을 조회해 중복 주문을 막는다.</li>
 *   <li>KIS 가 내용을 보고 거절한 주문(잔고 부족, 장 시간 아님 등)은 다시 보내도 같은 결과이므로 바로 FAILED 로 둔다.
 *       (app.trading.retry-rejected-orders=true 로 바꾸면 이런 주문도 재시도한다.)</li>
 *   <li>자동 실행이 낸 주문은 스케줄러가 대신 다시 보내지 않는다. 이미 지난 판단으로 주문이 나가거나 같은 종목이 두 번 사지는 일을 막기 위해서다.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderQueueService {

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	public enum State {
		SUCCESS, RETRY, FAILED
	}

	public record Attempt(State state, OrderOutcome outcome) {
	}

	private final KisOrderRequestRepository requests;
	private final TradingService trading;
	private final DailyOrderCap cap;
	private final AppProperties props;
	private final TransactionTemplate tx;

	/** 한도 확인 → 원장 적재 → 전송. 호출한 쪽은 결과(성공/대기/실패)를 바로 받는다. */
	public OrderOutcome place(MemberAccount account, Plan plan, OrderSource source) {
		cap.reserve(account, plan);
		KisOrderRequest saved;
		try {
			saved = tx.execute(s -> requests.save(KisOrderRequest.create(account, plan, source)));
		} catch (RuntimeException e) {
			cap.release(account, plan);
			throw e;
		}
		return attempt(saved.getId(), account).outcome();
	}

	/** 원장의 한 건을 KIS 로 보낸다(첫 시도와 재시도 모두). */
	public Attempt attempt(Long requestId, MemberAccount account) {
		KisOrderRequest row = tx.execute(s -> {
			KisOrderRequest r = requests.findById(requestId).orElseThrow();
			r.beginAttempt();
			return r;
		});
		OrderRequest req = new OrderRequest(row.getMarket(), row.getSymbol(), row.getSide(), row.getQuantity(), row.getLimitPrice());
		try {
			OrderOutcome out = trading.sendOrder(account, req);
			tx.executeWithoutResult(s -> requests.findById(requestId).ifPresent(r -> r.markSuccess(out.orderNo())));
			log.info("주문 접수 request={} account={} {} {} x{} orderNo={} attempts={}", requestId, account.getId(), row.getSide(),
				row.getSymbol(), row.getQuantity(), out.orderNo(), row.getAttempts());
			return new Attempt(State.SUCCESS, out);
		} catch (ApiException e) {
			// 요청 자체가 잘못된 경우(수량·가격 규칙 등). 다시 보내도 소용없다.
			finalFail(requestId, account, row, KisFailure.REJECTED, e.getMessage());
			throw e;
		} catch (KisClientException e) {
			return afterFailure(requestId, account, row, e);
		}
	}

	private Attempt afterFailure(Long requestId, MemberAccount account, KisOrderRequest row, KisClientException e) {
		KisFailure kind = KisFailure.classify(e);
		String message = friendly(e);
		boolean retryable = row.getSourceType() != OrderSource.Type.AGENT
			&& row.getAttempts() <= KisLimits.ORDER_MAX_RETRIES
			&& (kind.isTransient() || props.getTrading().isRetryRejectedOrders());
		log.warn("주문 실패 request={} account={} attempts={} kind={} retry={}: {}", requestId, account.getId(), row.getAttempts(),
			kind, retryable, e.getMessage());
		OrderOutcome failed = failedOutcome(row, message);
		if (!retryable) {
			finalFail(requestId, account, row, kind, message);
			return new Attempt(State.FAILED, failed);
		}
		LocalDateTime next = LocalDateTime.now(SEOUL).plusSeconds(KisLimits.backoffSeconds(row.getAttempts()));
		tx.executeWithoutResult(s -> requests.findById(requestId).ifPresent(r -> r.markRetry(next, kind, message)));
		String pendingMessage = "일시적인 오류로 주문이 아직 접수되지 않았습니다(" + message + "). 자동으로 최대 "
			+ KisLimits.ORDER_MAX_RETRIES + "번 다시 시도하며, 진행 상황은 '주문 큐'에서 확인할 수 있습니다.";
		return new Attempt(State.RETRY, failed.asPending(pendingMessage));
	}

	private void finalFail(Long requestId, MemberAccount account, KisOrderRequest row, KisFailure kind, String message) {
		tx.executeWithoutResult(s -> requests.findById(requestId).ifPresent(r -> r.markFailed(kind, message)));
		if (account != null) {
			cap.release(account, row.toPlan());
		}
	}

	private static OrderOutcome failedOutcome(KisOrderRequest row, String message) {
		return new OrderOutcome(false, message, null, row.getSide(), row.getSymbol(), row.getName(), row.getMarket(),
			row.getMarket().currency(), row.getQuantity(), row.getLimitPrice());
	}

	private static String friendly(KisClientException e) {
		String m = e.getMessage() == null ? "" : e.getMessage();
		return switch (KisFailure.classify(e)) {
			case RATE_LIMIT -> "증권사 호출 한도를 잠시 초과했습니다";
			case TOKEN -> "증권사 접속 인증을 갱신하는 중입니다";
			case NETWORK -> "증권사 서버 응답을 받지 못했습니다";
			case SERVER -> "증권사 서버에 일시적인 문제가 있습니다";
			case REJECTED -> m.isBlank() ? "증권사가 주문을 거절했습니다." : m;
		};
	}

	/**
	 * 응답을 받지 못해 접수 여부가 불확실했던 주문을 다시 보내기 전에, 오늘 주문 내역에 같은 주문이 이미 있는지 찾는다.
	 * 찾으면 그 주문번호를 돌려준다. 조회 자체가 실패하면 비어 있는 것으로 보지 않고 예외를 던져 이번 재시도를 미룬다.
	 */
	public Optional<String> findAlreadyPlaced(MemberAccount account, KisOrderRequest row) {
		LocalDate today = LocalDate.now(SEOUL);
		LocalDateTime since = row.getCreatedAt().minusMinutes(2);
		List<OrderHistoryItem> items = trading.orderHistory(account, row.getMarket().overseas(), today.minusDays(1), today, 0, 100)
			.getContent();
		return items.stream()
			.filter(i -> i.side() != null && i.side().equalsIgnoreCase(row.getSide()))
			.filter(i -> row.getSymbol().equalsIgnoreCase(i.productNumber()))
			.filter(i -> i.orderQuantity() == row.getQuantity())
			.filter(i -> i.orderedAt() == null || !i.orderedAt().atZoneSameInstant(SEOUL).toLocalDateTime().isBefore(since))
			.map(OrderHistoryItem::orderNo)
			.findFirst();
	}

	public void markFoundOrder(Long requestId, String orderNo) {
		tx.executeWithoutResult(s -> requests.findById(requestId).ifPresent(r -> r.markSuccess(orderNo)));
	}

	public void markCancelledOrMissing(Long requestId, MemberAccount account, KisOrderRequest row, String reason) {
		finalFail(requestId, account, row, KisFailure.REJECTED, reason);
	}
}
