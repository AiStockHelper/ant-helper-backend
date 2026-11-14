package com.backend.market.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.backend.common.exception.ApiException;
import com.backend.common.exception.ErrorCode;
import com.backend.common.properties.AppProperties;
import com.backend.domains.member.domain.MemberAccount;
import com.backend.domains.trade.DailyOrderCap;
import com.backend.domains.trade.TradePlanner.Plan;
import com.backend.market.Market;
import com.backend.market.TradingService;
import com.backend.market.dto.MarketDtos.OrderOutcome;
import com.backend.market.queue.OrderQueueService.Attempt;
import com.backend.market.queue.OrderQueueService.State;
import com.backend.order.kis.enums.AccountType;
import com.backend.order.kis.kis_client.exception.InvalidApiRequestException;
import com.backend.order.kis.kis_client.exception.KisClientException;

class OrderQueueServiceTest {

	private final Map<Long, KisOrderRequest> db = new HashMap<>();
	private final AtomicLong ids = new AtomicLong();
	private KisOrderRequestRepository repo;
	private TradingService trading;
	private DailyOrderCap cap;
	private AppProperties props;
	private OrderQueueService queue;
	private MemberAccount account;

	@BeforeEach
	void setUp() {
		repo = mock(KisOrderRequestRepository.class);
		when(repo.save(any())).thenAnswer(inv -> {
			KisOrderRequest r = inv.getArgument(0);
			if (r.getId() == null) {
				ReflectionTestUtils.setField(r, "id", ids.incrementAndGet());
			}
			db.put(r.getId(), r);
			return r;
		});
		when(repo.findById(anyLong())).thenAnswer(inv -> Optional.ofNullable(db.get(inv.getArgument(0))));
		trading = mock(TradingService.class);
		cap = mock(DailyOrderCap.class);
		props = new AppProperties();
		queue = new OrderQueueService(repo, trading, cap, props, new TransactionTemplate(mock(PlatformTransactionManager.class)));
		account = account(AccountType.REAL_TRADE);
	}

	private static MemberAccount account(AccountType type) {
		MemberAccount a = MemberAccount.builder().memberId(1L).accountType(type).appKey("a").appKeySalt("v2").secretKey("s")
			.secretKeySalt("v2").accountNumber("12345678").accountProductCode("01").build();
		ReflectionTestUtils.setField(a, "id", 5L);
		return a;
	}

	private static Plan plan() {
		return new Plan(Market.KRX, "005930", "삼성전자", "BUY", 3, null, BigDecimal.valueOf(70_000), "시장가");
	}

	private OrderOutcome ok(String no) {
		return new OrderOutcome(true, "ok", no, "BUY", "005930", "삼성전자", Market.KRX, Market.KRX.currency(), 3, null);
	}

	private KisOrderRequest only() {
		assertThat(db).hasSize(1);
		return db.values().iterator().next();
	}

	private static KisClientException rateLimit() {
		return new InvalidApiRequestException("{\"rt_cd\":\"1\",\"msg_cd\":\"EGW00201\",\"msg1\":\"초당 거래건수를 초과하였습니다.\"}", 500);
	}

	@Test
	void successIsRecordedWithOrderNo() {
		when(trading.sendOrder(any(), any())).thenReturn(ok("0001234"));
		OrderOutcome out = queue.place(account, plan(), OrderSource.chat(9L, 3L));
		assertThat(out.success()).isTrue();
		KisOrderRequest r = only();
		assertThat(r.getStatus()).isEqualTo(KisOrderRequest.Status.SUCCESS);
		assertThat(r.getOrderNo()).isEqualTo("0001234");
		assertThat(r.getAttempts()).isEqualTo(1);
		verify(cap).reserve(any(), any());
		verify(cap, never()).release(any(), any());
	}

	@Test
	void transientFailureIsQueuedForRetryWithBackoff() {
		when(trading.sendOrder(any(), any())).thenThrow(rateLimit());
		OrderOutcome out = queue.place(account, plan(), OrderSource.chat(9L, 3L));
		assertThat(out.success()).isFalse();
		assertThat(out.pending()).isTrue();
		KisOrderRequest r = only();
		assertThat(r.getStatus()).isEqualTo(KisOrderRequest.Status.RETRY_WAIT);
		assertThat(r.getAttempts()).isEqualTo(1);
		assertThat(r.getNextAttemptAt()).isAfter(java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Seoul")));
		assertThat(r.getFailureKind()).isEqualTo(KisFailure.RATE_LIMIT);
		verify(cap, never()).release(any(), any());   // 아직 주문이 살아 있으므로 한도는 계속 잡아 둔다
	}

	@Test
	void givesUpAfterTenRetriesAndShowsInFailedList() {
		when(trading.sendOrder(any(), any())).thenThrow(rateLimit());
		queue.place(account, plan(), OrderSource.chat(9L, 3L));
		Long id = only().getId();
		for (int retry = 1; retry <= KisLimits.ORDER_MAX_RETRIES; retry++) {
			Attempt a = queue.attempt(id, account);
			if (retry < KisLimits.ORDER_MAX_RETRIES) {
				assertThat(a.state()).as("retry %d", retry).isEqualTo(State.RETRY);
			} else {
				assertThat(a.state()).isEqualTo(State.FAILED);
			}
		}
		KisOrderRequest r = only();
		assertThat(r.getStatus()).isEqualTo(KisOrderRequest.Status.FAILED);
		assertThat(r.getAttempts()).isEqualTo(1 + KisLimits.ORDER_MAX_RETRIES);
		assertThat(r.getCompletedAt()).isNotNull();
		verify(cap).release(any(), any());
	}

	@Test
	void retrySucceedsLater() {
		when(trading.sendOrder(any(), any())).thenThrow(rateLimit()).thenThrow(rateLimit()).thenReturn(ok("77"));
		queue.place(account, plan(), OrderSource.chat(9L, 3L));
		Long id = only().getId();
		assertThat(queue.attempt(id, account).state()).isEqualTo(State.RETRY);
		Attempt last = queue.attempt(id, account);
		assertThat(last.state()).isEqualTo(State.SUCCESS);
		assertThat(only().getStatus()).isEqualTo(KisOrderRequest.Status.SUCCESS);
		assertThat(only().getOrderNo()).isEqualTo("77");
		assertThat(only().getAttempts()).isEqualTo(3);
	}

	@Test
	void businessRejectionFailsImmediatelyAndFreesTheDailyLimit() {
		when(trading.sendOrder(any(), any())).thenThrow(new KisClientException("모의투자 주문가능금액이 부족합니다."));
		OrderOutcome out = queue.place(account, plan(), OrderSource.chat(9L, 3L));
		assertThat(out.success()).isFalse();
		assertThat(out.pending()).isFalse();
		assertThat(out.message()).contains("주문가능금액");
		assertThat(only().getStatus()).isEqualTo(KisOrderRequest.Status.FAILED);
		assertThat(only().getAttempts()).isEqualTo(1);
		verify(cap).release(any(), any());
	}

	@Test
	void rejectionsAreRetriedWhenConfigured() {
		props.getTrading().setRetryRejectedOrders(true);
		when(trading.sendOrder(any(), any())).thenThrow(new KisClientException("거절"));
		OrderOutcome out = queue.place(account, plan(), OrderSource.chat(9L, 3L));
		assertThat(out.pending()).isTrue();
		assertThat(only().getStatus()).isEqualTo(KisOrderRequest.Status.RETRY_WAIT);
	}

	@Test
	void agentOrdersAreNeverRetriedBehindTheirBack() {
		when(trading.sendOrder(any(), any())).thenThrow(rateLimit());
		OrderOutcome out = queue.place(account, plan(), OrderSource.agent(4L, 8L, "상승 예측"));
		assertThat(out.pending()).isFalse();
		assertThat(out.success()).isFalse();
		assertThat(only().getStatus()).isEqualTo(KisOrderRequest.Status.FAILED);
		verify(cap).release(any(), any());
	}

	@Test
	void invalidRequestIsRethrownAndRecordedAsFailed() {
		when(trading.sendOrder(any(), any())).thenThrow(ApiException.of(ErrorCode.ORDER_INVALID, "미국 주식은 지정가 주문만 가능합니다."));
		assertThatThrownBy(() -> queue.place(account, plan(), OrderSource.manual())).isInstanceOf(ApiException.class);
		assertThat(only().getStatus()).isEqualTo(KisOrderRequest.Status.FAILED);
		verify(cap).release(any(), any());
	}

	@Test
	void dailyCapRejectionNeverReachesTheLedgerOrKis() {
		org.mockito.Mockito.doThrow(ApiException.of(ErrorCode.DAILY_LIMIT_EXCEEDED, "한도")).when(cap).reserve(any(), any());
		assertThatThrownBy(() -> queue.place(account, plan(), OrderSource.manual())).isInstanceOf(ApiException.class);
		assertThat(db).isEmpty();
		verify(trading, never()).sendOrder(any(), any());
	}

	@Test
	void ambiguousFailureIsMarkedSoItIsCheckedBeforeResending() {
		when(trading.sendOrder(any(), any())).thenThrow(new InvalidApiRequestException(
			"Failed to get response from server. (timed out)", new java.io.IOException("timed out")));
		queue.place(account, plan(), OrderSource.chat(9L, 3L));
		assertThat(only().getFailureKind()).isEqualTo(KisFailure.NETWORK);
		assertThat(only().getFailureKind().isAmbiguous()).isTrue();
	}
}
