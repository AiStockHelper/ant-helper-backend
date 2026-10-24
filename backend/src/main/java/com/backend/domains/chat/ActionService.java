package com.backend.domains.chat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.backend.common.exception.ApiException;
import com.backend.common.exception.ErrorCode;
import com.backend.common.properties.AppProperties;
import com.backend.common.util.Json;
import com.backend.domains.account.AccountService;
import com.backend.domains.account.AccountService.AccountDeletedEvent;
import com.backend.domains.chat.dto.ChatDtos.Action;
import com.backend.domains.chat.dto.ChatDtos.ActionResult;
import com.backend.domains.chat.dto.ChatDtos.Detail;
import com.backend.domains.chat.entity.ChatAction;
import com.backend.domains.chat.repository.ChatActionRepository;
import com.backend.domains.chat.repository.ChatConversationRepository;
import com.backend.domains.member.domain.MemberAccount;
import com.backend.domains.schedule.ScheduleService;
import com.backend.domains.schedule.ScheduledTask;
import com.backend.domains.trade.TradePlanner;
import com.backend.domains.trade.TradePlanner.Plan;
import com.backend.market.Market;
import com.backend.market.MarketDataService;
import com.backend.market.dto.MarketDtos.OrderOutcome;
import com.backend.market.dto.MarketDtos.OrderRequest;
import com.backend.market.TradingService;
import com.fasterxml.jackson.core.type.TypeReference;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 실전 계좌의 주문/예약은 반드시 이 서비스의 승인 절차를 거친다.
 * 승인 전에는 KIS 로 아무 요청도 나가지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ActionService {

	private final ChatActionRepository actions;
	private final ChatConversationRepository conversations;
	private final ChatMapper mapper;
	private final Json json;
	private final AppProperties props;
	private final AccountService accountService;
	private final TradePlanner planner;
	private final TradingService tradingService;
	private final MarketDataService marketData;
	private final ScheduleService scheduleService;
	private final TransactionTemplate tx;

	// ─────────────────────────── 생성 ───────────────────────────

	public Action createOrderAction(MemberAccount account, Long conversationId, Long messageId, Plan plan) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("market", plan.market().name());
		payload.put("productNumber", plan.symbol());
		payload.put("side", plan.side());
		payload.put("quantity", plan.quantity());
		payload.put("limitPrice", plan.limitPrice());
		String title = plan.name() + " " + plan.quantity() + "주 " + plan.sideLabel();
		String summary = plan.currency().format(plan.estimatedAmount()) + " 규모 " + plan.orderTypeLabel() + " " + plan.sideLabel()
			+ " 주문입니다. 승인하면 즉시 주문이 전송됩니다.";
		return create(account, conversationId, messageId, ChatAction.Kind.ORDER, title, summary,
			planner.details(account, plan), payload);
	}

	public Action createScheduledOrderAction(MemberAccount account, Long conversationId, Long messageId, Plan plan,
		Map<String, Object> taskParams, Instant runAt, String runAtLabel, String taskTitle) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("taskKind", ScheduledTask.Kind.ORDER.name());
		payload.put("title", taskTitle);
		payload.put("runAt", runAt.toString());
		payload.put("params", taskParams);
		payload.put("marketForLimit", plan.market().name());
		List<Detail> details = new java.util.ArrayList<>(planner.details(account, plan));
		details.add(0, new Detail("실행 시각", runAtLabel));
		details.add(new Detail("가격 기준", "실행 시점의 시세로 다시 계산됩니다"));
		String summary = runAtLabel + "에 자동으로 주문합니다. 실행 시점의 시세로 수량·가격이 다시 계산될 수 있습니다.";
		return create(account, conversationId, messageId, ChatAction.Kind.SCHEDULED_ORDER, taskTitle, summary, details, payload);
	}

	public Action createScheduledRecommendBuyAction(MemberAccount account, Long conversationId, Long messageId,
		Market market, BigDecimal budget, int count, Instant runAt, String runAtLabel, String taskTitle,
		Map<String, Object> taskParams) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("taskKind", ScheduledTask.Kind.RECOMMEND_BUY.name());
		payload.put("title", taskTitle);
		payload.put("runAt", runAt.toString());
		payload.put("params", taskParams);
		payload.put("marketForLimit", market.name());
		List<Detail> details = List.of(
			new Detail("계좌", account.displayName() + " (" + (account.isPaper() ? "모의" : "실전") + ")"),
			new Detail("실행 시각", runAtLabel),
			new Detail("시장", market.overseas() ? "미국" : "한국"),
			new Detail("총 예산", market.currency().format(budget)),
			new Detail("매수 종목 수", count + "개 (예산을 균등 배분)"),
			new Detail("종목 선정", "실행 시점에 AI 예측으로 상승이 예상되는 상위 종목"),
			new Detail("주의", "실행 시점의 시세와 예측에 따라 종목·수량이 정해집니다"));
		String summary = runAtLabel + "에 AI가 추천 종목을 고르고 " + market.currency().format(budget) + " 한도로 매수합니다.";
		return create(account, conversationId, messageId, ChatAction.Kind.SCHEDULED_RECOMMEND_BUY, taskTitle, summary,
			details, payload);
	}

	private Action create(MemberAccount account, Long conversationId, Long messageId, ChatAction.Kind kind, String title,
		String summary, List<Detail> details, Map<String, Object> payload) {
		LocalDateTime expires = LocalDateTime.now().plusMinutes(props.getTrading().getActionTtlMinutes());
		ChatAction saved = tx.execute(s -> actions.save(new ChatAction(account.getMemberId(), conversationId, messageId,
			account.getId(), kind, title, summary, json.write(details), json.write(payload), expires)));
		return mapper.action(saved, account);
	}

	// ─────────────────────────── 조회/승인/거절 ───────────────────────────

	@Transactional(readOnly = true)
	public Action get(Long memberId, Long id) {
		return mapper.action(require(memberId, id));
	}

	public Action reject(Long memberId, Long id) {
		ChatAction a = require(memberId, id);
		if (a.getStatus() != ChatAction.Status.PENDING) {
			throw ApiException.from(ErrorCode.ACTION_NOT_PENDING);
		}
		return tx.execute(s -> {
			ChatAction fresh = actions.findById(id).orElseThrow();
			fresh.markStatus(ChatAction.Status.REJECTED, json.write(new ActionResult(false, "사용자가 거절했습니다.", null)));
			return mapper.action(fresh);
		});
	}

	public Action approve(Long memberId, Long id) {
		ChatAction a = require(memberId, id);
		if (a.getStatus() == ChatAction.Status.PENDING && a.isExpired()) {
			tx.executeWithoutResult(s -> actions.findById(id).ifPresent(x -> x.markStatus(ChatAction.Status.EXPIRED,
				json.write(new ActionResult(false, "확인 유효시간이 지나 만료되었습니다.", null)))));
			return mapper.action(require(memberId, id));
		}
		Boolean claimed = tx.execute(s -> actions.claim(id) == 1);
		if (!Boolean.TRUE.equals(claimed)) {
			throw ApiException.from(ErrorCode.ACTION_NOT_PENDING);
		}

		ActionResult result;
		boolean ok;
		try {
			MemberAccount account = accountService.requireOwned(memberId, a.getAccountId());
			Map<String, Object> payload = json.read(a.getPayloadJson(), new TypeReference<Map<String, Object>>() {}, Map.of());
			result = switch (a.getKind()) {
				case ORDER -> executeOrder(account, payload);
				case SCHEDULED_ORDER, SCHEDULED_RECOMMEND_BUY -> registerTask(account, a, payload);
			};
			ok = result.success();
		} catch (ApiException e) {
			result = new ActionResult(false, e.getMessage(), null);
			ok = false;
		} catch (RuntimeException e) {
			log.warn("확인 카드 {} 실행 중 오류", id, e);
			result = new ActionResult(false, "처리 중 오류가 발생했습니다: " + e.getMessage(), null);
			ok = false;
		}
		final ActionResult finalResult = result;
		final ChatAction.Status status = ok ? ChatAction.Status.EXECUTED : ChatAction.Status.FAILED;
		return tx.execute(s -> {
			ChatAction fresh = actions.findById(id).orElseThrow();
			fresh.markStatus(status, json.write(finalResult));
			return mapper.action(fresh);
		});
	}

	private ActionResult executeOrder(MemberAccount account, Map<String, Object> p) {
		Market market = Market.parse(String.valueOf(p.get("market")));
		String symbol = String.valueOf(p.get("productNumber"));
		String side = String.valueOf(p.get("side"));
		long qty = ((Number)p.get("quantity")).longValue();
		BigDecimal limit = p.get("limitPrice") == null ? null : new BigDecimal(p.get("limitPrice").toString());
		String name = marketData.nameOf(market, symbol);

		// 승인 시점에 한도/장 운영을 다시 확인한다(카드를 오래 열어 둔 경우 대비).
		BigDecimal ref = limit != null ? limit : marketData.quote(account, market, symbol).price();
		Plan plan = new Plan(market, symbol, name, side, qty, limit, ref, limit == null ? "시장가" : "지정가");
		planner.enforceLimits(account, plan);
		planner.requireMarketOpen(market);

		OrderOutcome out = tradingService.placeOrder(account, new OrderRequest(market, symbol, side, qty, limit));
		return new ActionResult(out.success(), out.success()
			? plan.name() + " " + qty + "주 " + plan.sideLabel() + " 주문이 접수되었습니다." + (out.orderNo() != null ? " (주문번호 " + out.orderNo() + ")" : "")
			: "주문이 거절되었습니다: " + out.message(), out.orderNo());
	}

	private ActionResult registerTask(MemberAccount account, ChatAction a, Map<String, Object> payload) {
		Instant runAt = Instant.parse(String.valueOf(payload.get("runAt")));
		if (runAt.isBefore(Instant.now().plusSeconds(5))) {
			return new ActionResult(false, "예약 실행 시각이 이미 지났습니다. 다시 요청해 주세요.", null);
		}
		@SuppressWarnings("unchecked")
		Map<String, Object> params = (Map<String, Object>)payload.get("params");
		ScheduledTask.Kind kind = ScheduledTask.Kind.valueOf(String.valueOf(payload.get("taskKind")));
		ScheduledTask t = scheduleService.create(account, a.getConversationId(), kind, String.valueOf(payload.get("title")),
			runAt, params);
		return new ActionResult(true, "예약되었습니다. (예약 번호 " + t.getId() + ") 실행 결과는 이 대화와 '내 계좌 > 예약'에서 확인할 수 있습니다.",
			String.valueOf(t.getId()));
	}

	private ChatAction require(Long memberId, Long id) {
		return actions.findByIdAndMemberId(id, memberId).orElseThrow(() -> ApiException.from(ErrorCode.ACTION_NOT_FOUND));
	}

	// ─────────────────────────── 이벤트 ───────────────────────────

	@EventListener
	@Transactional
	public void onAccountDeleted(AccountDeletedEvent event) {
		actions.expirePendingForAccount(event.accountId());
		conversations.clearAccount(event.accountId());
	}
}
