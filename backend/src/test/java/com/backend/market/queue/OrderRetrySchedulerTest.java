package com.backend.market.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.backend.common.dto.PageResponse;
import com.backend.common.properties.AppProperties;
import com.backend.domains.chat.repository.ChatConversationRepository;
import com.backend.domains.chat.repository.ChatMessageRepository;
import com.backend.domains.email.EmailUtil;
import com.backend.domains.member.domain.MemberAccount;
import com.backend.domains.member.repository.MemberAccountRepository;
import com.backend.domains.member.repository.MemberRepository;
import com.backend.domains.trade.DailyOrderCap;
import com.backend.domains.trade.TradePlanner.Plan;
import com.backend.market.Market;
import com.backend.market.TradingService;
import com.backend.market.dto.MarketDtos.OrderHistoryItem;
import com.backend.market.dto.MarketDtos.OrderOutcome;
import com.backend.order.kis.enums.AccountType;
import com.backend.order.kis.kis_client.exception.InvalidApiRequestException;

class OrderRetrySchedulerTest {

	private final Map<Long, KisOrderRequest> db = new HashMap<>();
	private final AtomicLong ids = new AtomicLong();
	private KisOrderRequestRepository repo;
	private TradingService trading;
	private MemberAccountRepository accounts;
	private OrderRetryScheduler scheduler;
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
		when(repo.claim(anyLong(), any())).thenReturn(1);
		when(repo.findTop20ByStatusAndNextAttemptAtLessThanEqualOrderByIdAsc(any(), any()))
			.thenAnswer(inv -> db.values().stream().filter(r -> r.getStatus() == KisOrderRequest.Status.RETRY_WAIT).toList());
		when(repo.findByStatusAndUpdatedAtBefore(any(), any())).thenReturn(List.of());

		trading = mock(TradingService.class);
		TransactionTemplate tx = new TransactionTemplate(mock(PlatformTransactionManager.class));
		queue = new OrderQueueService(repo, trading, mock(DailyOrderCap.class), new AppProperties(), tx);
		account = MemberAccount.builder().memberId(1L).accountType(AccountType.REAL_TRADE).appKey("a").appKeySalt("v2")
			.secretKey("s").secretKeySalt("v2").accountNumber("12345678").accountProductCode("01").build();
		ReflectionTestUtils.setField(account, "id", 5L);
		accounts = mock(MemberAccountRepository.class);
		when(accounts.findById(5L)).thenReturn(Optional.of(account));
		scheduler = new OrderRetryScheduler(repo, accounts, mock(MemberRepository.class), mock(ChatConversationRepository.class),
			mock(ChatMessageRepository.class), queue, mock(EmailUtil.class), tx);
	}

	private KisOrderRequest waiting(KisFailure kind) {
		Plan plan = new Plan(Market.KRX, "005930", "삼성전자", "BUY", 3, null, BigDecimal.valueOf(70_000), "시장가");
		KisOrderRequest r = KisOrderRequest.create(account, plan, OrderSource.chat(1L, 2L));
		repo.save(r);
		r.beginAttempt();
		r.markRetry(java.time.LocalDateTime.now().minusSeconds(1), kind, "전에 실패");
		return r;
	}

	private OrderOutcome ok(String no) {
		return new OrderOutcome(true, "ok", no, "BUY", "005930", "삼성전자", Market.KRX, Market.KRX.currency(), 3, null);
	}

	@SuppressWarnings("unchecked")
	private PageResponse<OrderHistoryItem> history(List<OrderHistoryItem> items) {
		PageResponse<OrderHistoryItem> page = mock(PageResponse.class);
		when(page.getContent()).thenReturn(items);
		return page;
	}

	@Test
	void dueRetryIsSentAndMarkedSuccess() {
		KisOrderRequest r = waiting(KisFailure.RATE_LIMIT);
		when(trading.sendOrder(any(), any())).thenReturn(ok("555"));
		scheduler.tick();
		assertThat(r.getStatus()).isEqualTo(KisOrderRequest.Status.SUCCESS);
		assertThat(r.getOrderNo()).isEqualTo("555");
		assertThat(r.getAttempts()).isEqualTo(2);
	}

	@Test
	void ambiguousFailureIsNotResentWhenTheOrderAlreadyExists() {
		KisOrderRequest r = waiting(KisFailure.NETWORK);
		OrderHistoryItem existing = new OrderHistoryItem("0009", OffsetDateTime.now(ZoneOffset.ofHours(9)), "BUY", "005930", "삼성전자",
			Market.KRX, Market.KRX.currency(), 3, null, 0, null, "PENDING");
		PageResponse<OrderHistoryItem> page = history(List.of(existing));
		when(trading.orderHistory(any(), org.mockito.ArgumentMatchers.anyBoolean(), any(), any(), org.mockito.ArgumentMatchers.anyInt(),
			org.mockito.ArgumentMatchers.anyInt())).thenReturn(page);
		scheduler.tick();
		assertThat(r.getStatus()).isEqualTo(KisOrderRequest.Status.SUCCESS);
		assertThat(r.getOrderNo()).isEqualTo("0009");
		verify(trading, never()).sendOrder(any(), any());   // 중복 주문을 내지 않았다
	}

	@Test
	void ambiguousFailureIsResentWhenNoSuchOrderExists() {
		KisOrderRequest r = waiting(KisFailure.SERVER);
		PageResponse<OrderHistoryItem> page = history(List.of());
		when(trading.orderHistory(any(), org.mockito.ArgumentMatchers.anyBoolean(), any(), any(), org.mockito.ArgumentMatchers.anyInt(),
			org.mockito.ArgumentMatchers.anyInt())).thenReturn(page);
		when(trading.sendOrder(any(), any())).thenReturn(ok("123"));
		scheduler.tick();
		assertThat(r.getStatus()).isEqualTo(KisOrderRequest.Status.SUCCESS);
		verify(trading).sendOrder(any(), any());
	}

	@Test
	void failedLookupDefersTheRetryWithoutSpendingAnAttempt() {
		KisOrderRequest r = waiting(KisFailure.NETWORK);
		when(trading.orderHistory(any(), org.mockito.ArgumentMatchers.anyBoolean(), any(), any(), org.mockito.ArgumentMatchers.anyInt(),
			org.mockito.ArgumentMatchers.anyInt())).thenThrow(new InvalidApiRequestException("timeout", new java.io.IOException("x")));
		scheduler.tick();
		assertThat(r.getStatus()).isEqualTo(KisOrderRequest.Status.RETRY_WAIT);
		assertThat(r.getAttempts()).isEqualTo(1);
		verify(trading, never()).sendOrder(any(), any());
	}

	@Test
	void orderOfADeletedAccountIsClosedOut() {
		KisOrderRequest r = waiting(KisFailure.RATE_LIMIT);
		when(accounts.findById(5L)).thenReturn(Optional.empty());
		scheduler.tick();
		assertThat(r.getStatus()).isEqualTo(KisOrderRequest.Status.FAILED);
		verify(trading, never()).sendOrder(any(), any());
		verify(repo, never()).deleteById(anyLong());
	}
}
