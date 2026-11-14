package com.backend.domains.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import com.backend.common.util.Json;
import com.backend.domains.member.domain.MemberAccount;
import com.backend.domains.trade.TradePlanner;
import com.backend.market.Currency;
import com.backend.market.Market;
import com.backend.market.TradingService;
import com.backend.market.dto.MarketDtos.OrderHistoryItem;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 한국투자증권 앱 등 앱 밖에서 직접 매매한 것이 자동 실행 장부에 올바르게 반영되는지 확인한다. */
class ExternalTradeSyncTest {

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
	private static final long LEDGER = 77L;

	private AgentPositionRepository positions;
	private AgentTradeRepository trades;
	private TradingService tradingService;
	private AgentTradingService service;
	private AgentTask task;
	private MemberAccount account;
	private AgentPosition position;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		positions = mock(AgentPositionRepository.class);
		trades = mock(AgentTradeRepository.class);
		tradingService = mock(TradingService.class);
		TransactionTemplate tx = mock(TransactionTemplate.class);
		doAnswer(inv -> {
			((Consumer<TransactionStatus>)inv.getArgument(0)).accept(null);
			return null;
		}).when(tx).executeWithoutResult(any());
		service = new AgentTradingService(mock(AgentTaskRepository.class), positions, trades, mock(TradePlanner.class), tradingService,
			new Json(new ObjectMapper().findAndRegisterModules()), tx);
		task = new AgentTask(1L, 2L, 3L, "multi-factor-trader", "t", null, "{}", "{}", null, true, Instant.now(), null, LEDGER, 0,
			AgentTask.CreatedBy.USER);
		account = mock(MemberAccount.class);
		position = new AgentPosition(LEDGER, 1L, 2L, Market.KRX, "005930", "삼성전자", 10, new BigDecimal("70000"));
		position.mark(new BigDecimal("71000"));
		when(positions.findByLedgerIdAndStatus(LEDGER, AgentPosition.Status.OPEN)).thenReturn(List.of(position));
		when(positions.findById(any())).thenReturn(Optional.of(position));
		when(trades.withOrderNo(LEDGER)).thenReturn(List.of());
		when(trades.findTop40ByLedgerIdOrderByIdDesc(LEDGER)).thenReturn(List.of());
	}

	private static OrderHistoryItem order(String no, long minutesAfterOpen, String side, String symbol, long exec, String price, String status) {
		return new OrderHistoryItem(no, OffsetDateTime.now(SEOUL).plusMinutes(minutesAfterOpen), side, symbol, "x", Market.KRX, Currency.KRW,
			exec, BigDecimal.ZERO, exec, new BigDecimal(price), status);
	}

	private void history(OrderHistoryItem... items) {
		when(tradingService.orders(any(), anyBoolean(), any(LocalDate.class), any(LocalDate.class))).thenReturn(List.of(items));
	}

	// ─────────────────────────── 매처(순수 계산) ───────────────────────────

	@Test
	void matcherCombinesOnlyUnknownSellsOldestFirstAndCapsAtTheMissingQuantity() {
		Instant since = Instant.now().minusSeconds(3600);
		List<OrderHistoryItem> h = List.of(
			order("A1", 5, "SELL", "005930", 3, "72000", "FILLED"),
			order("A2", 1, "SELL", "005930", 2, "70000", "FILLED"),
			order("B1", 2, "BUY", "005930", 9, "69000", "FILLED"),            // 매수는 제외
			order("C1", 3, "SELL", "000660", 5, "190000", "FILLED"),         // 다른 종목은 제외
			order("D1", 4, "SELL", "005930", 0, "72000", "PENDING"));        // 체결 0 은 제외
		var a = ExternalTradeMatcher.externalSells(h, Set.of(), Market.KRX, "005930", since, 4, SEOUL);
		assertThat(a.quantity()).isEqualTo(4);                                  // A2(2주@70,000) + A1 일부(2주@72,000)
		assertThat(a.averagePrice()).isEqualByComparingTo("71000");
		assertThat(a.firstOrderNo()).isEqualTo("A2");
		assertThat(a.orders()).isEqualTo(2);
	}

	@Test
	void matcherSkipsOrdersTheAppPlacedItselfAndOrdersBeforeThePositionOpened() {
		Instant since = Instant.now();
		OrderHistoryItem own = order("OWN", 5, "SELL", "005930", 3, "72000", "FILLED");
		OrderHistoryItem old = order("OLD", -600, "SELL", "005930", 3, "72000", "FILLED");
		OrderHistoryItem ext = order("EXT", 6, "SELL", "005930", 3, "73000", "FILLED");
		String ownKey = ExternalTradeMatcher.key(own.orderedAt().atZoneSameInstant(SEOUL).toLocalDate(), "OWN");
		var a = ExternalTradeMatcher.externalSells(List.of(own, old, ext), Set.of(ownKey), Market.KRX, "005930", since, 10, SEOUL);
		assertThat(a.firstOrderNo()).isEqualTo("EXT");
		assertThat(a.quantity()).isEqualTo(3);
		assertThat(ExternalTradeMatcher.externalSells(List.of(own), Set.of(ownKey), Market.KRX, "005930", since, 10, SEOUL)).isNull();
		assertThat(ExternalTradeMatcher.externalSells(null, Set.of(), Market.KRX, "005930", since, 10, SEOUL)).isNull();
	}

	@Test
	void matcherDetectsPendingBuys() {
		assertThat(ExternalTradeMatcher.hasPendingBuy(List.of(order("P", 0, "BUY", "005930", 0, "70000", "PENDING")), Market.KRX, "005930")).isTrue();
		assertThat(ExternalTradeMatcher.hasPendingBuy(List.of(order("P", 0, "BUY", "005930", 5, "70000", "FILLED")), Market.KRX, "005930")).isFalse();
		assertThat(ExternalTradeMatcher.hasPendingBuy(List.of(order("P", 0, "BUY", "000660", 0, "70000", "PENDING")), Market.KRX, "005930")).isFalse();
	}

	// ─────────────────────────── 장부 반영 ───────────────────────────

	@Test
	void anExternalSellIsBookedAtTheRealFillPriceWithRealizedProfit() {
		history(order("X9", 3, "SELL", "005930", 4, "72000", "FILLED"));

		List<String> lines = service.reconcile(task, account, Map.of("KRX:005930", 6L));

		assertThat(position.getQuantity()).isEqualTo(6);
		assertThat(position.getRealizedPnl()).isEqualByComparingTo("8000");                 // (72,000-70,000)×4
		assertThat(position.getStatus()).isEqualTo(AgentPosition.Status.OPEN);
		ArgumentCaptor<AgentTrade> saved = ArgumentCaptor.forClass(AgentTrade.class);
		verify(trades, times(1)).save(saved.capture());
		assertThat(saved.getValue().getSide()).isEqualTo("SELL");
		assertThat(saved.getValue().getQuantity()).isEqualTo(4);
		assertThat(saved.getValue().getOrderNo()).isEqualTo("X9");
		assertThat(saved.getValue().getReason()).contains("직접 매도");
		assertThat(lines).hasSize(1);
		assertThat(lines.get(0)).contains("삼성전자 4주").contains("직접 매도").contains("+8,000");
	}

	@Test
	void sellingEverythingOutsideTheAppClosesThePositionAndKeepsTheLoss() {
		history(order("X1", 2, "SELL", "005930", 10, "68000", "FILLED"));

		service.reconcile(task, account, Map.of());

		assertThat(position.getStatus()).isEqualTo(AgentPosition.Status.CLOSED);
		assertThat(position.getRealizedPnl()).isEqualByComparingTo("-20000");               // (68,000-70,000)×10
		assertThat(position.getExitReason()).contains("직접 매도");
	}

	@Test
	void whenNoFillIsFoundItIsEstimatedAtTheLastPriceAndSaidSo() {
		history();

		List<String> lines = service.reconcile(task, account, Map.of("KRX:005930", 7L));

		assertThat(position.getQuantity()).isEqualTo(7);
		assertThat(position.getRealizedPnl()).isEqualByComparingTo("3000");                 // (71,000-70,000)×3
		assertThat(lines.get(0)).contains("체결 내역을 찾지 못해").contains("추정");
	}

	@Test
	void whenTheOrderHistoryCannotBeLoadedItFallsBackToTheEstimate() {
		when(tradingService.orders(any(), anyBoolean(), any(LocalDate.class), any(LocalDate.class))).thenThrow(new IllegalStateException("KIS down"));

		List<String> lines = service.reconcile(task, account, Map.of("KRX:005930", 9L));

		assertThat(position.getQuantity()).isEqualTo(9);
		assertThat(lines.get(0)).contains("추정");
	}

	@Test
	void aPartOfTheMissingQuantityConfirmedAndTheRestEstimated() {
		history(order("X2", 2, "SELL", "005930", 2, "72000", "FILLED"));

		List<String> lines = service.reconcile(task, account, Map.of("KRX:005930", 5L));       // 5주 줄어듦: 2주는 확인, 3주는 추정

		assertThat(position.getQuantity()).isEqualTo(5);
		assertThat(lines).hasSize(2);
		verify(trades, times(2)).save(any(AgentTrade.class));
	}

	@Test
	void ordersTheAppPlacedItselfAreNotMistakenForOutsideTrades() {
		OrderHistoryItem own = order("MINE", 2, "SELL", "005930", 4, "72000", "FILLED");
		AgentTrade ownTrade = new AgentTrade(LEDGER, 1L, 1L, "SELL", Market.KRX, "005930", "삼성전자", 4, new BigDecimal("72000"), "MINE", "익절");
		when(trades.withOrderNo(LEDGER)).thenReturn(List.of(ownTrade));
		history(own);

		List<String> lines = service.reconcile(task, account, Map.of("KRX:005930", 6L));

		// 앱이 낸 매도는 이미 장부에 있으니 직접 매도로 세지 않고, 줄어든 수량은 추정으로만 정리된다
		assertThat(lines.get(0)).contains("추정");
	}

	@Test
	void aBuyTheAppJustPlacedIsNotTreatedAsAMissingHolding() {
		AgentTrade justBought = new AgentTrade(LEDGER, 1L, 1L, "BUY", Market.KRX, "005930", "삼성전자", 10, new BigDecimal("70000"), "B1", "신규");
		ReflectionTestUtils.setField(justBought, "createdAt", Instant.now().minusSeconds(120));
		when(trades.findTop40ByLedgerIdOrderByIdDesc(LEDGER)).thenReturn(List.of(justBought));
		history();

		assertThat(service.reconcile(task, account, Map.of())).isEmpty();
		assertThat(position.getQuantity()).isEqualTo(10);
		verify(trades, never()).save(any(AgentTrade.class));
	}

	@Test
	void aPendingBuyOrderMeansTheHoldingIsJustNotSettledYet() {
		history(order("P1", 0, "BUY", "005930", 0, "70000", "PENDING"));

		assertThat(service.reconcile(task, account, Map.of())).isEmpty();
		assertThat(position.getQuantity()).isEqualTo(10);
	}

	@Test
	void extraSharesBoughtOutsideTheAppAreLeftAlone() {
		history();

		assertThat(service.reconcile(task, account, Map.of("KRX:005930", 25L))).isEmpty();
		assertThat(position.getQuantity()).isEqualTo(10);
		assertThat(position.getRealizedPnl()).isEqualByComparingTo("0");
	}

	@Test
	void nothingHappensWhenTheLedgerHasNoOpenPositions() {
		when(positions.findByLedgerIdAndStatus(LEDGER, AgentPosition.Status.OPEN)).thenReturn(List.of());
		assertThat(service.reconcile(task, account, Map.of())).isEmpty();
		verify(tradingService, never()).summary(any());
	}
}
