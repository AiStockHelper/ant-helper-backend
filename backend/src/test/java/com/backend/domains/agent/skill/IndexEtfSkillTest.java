package com.backend.domains.agent.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.backend.common.exception.ApiException;
import com.backend.domains.agent.AgentPosition;
import com.backend.domains.agent.AgentTask;
import com.backend.domains.agent.AgentTradingService;
import com.backend.domains.agent.AgentTradingService.LedgerView;
import com.backend.domains.agent.Limits;
import com.backend.domains.member.domain.MemberAccount;
import com.backend.domains.trade.TradePlanner;
import com.backend.domains.trade.TradePlanner.Plan;
import com.backend.market.Currency;
import com.backend.market.Market;
import com.backend.market.MarketDataService;
import com.backend.market.MarketDataService.Period;
import com.backend.market.TradingCalendar;
import com.backend.market.dto.MarketDtos.Candle;
import com.backend.market.dto.MarketDtos.OrderOutcome;
import com.backend.market.dto.MarketDtos.Quote;
import com.backend.market.dto.MarketDtos.StockSummary;

class IndexEtfSkillTest {

	private static final ZoneId NY = ZoneId.of("America/New_York");
	/** 2026-11-10(화) 15:45 뉴욕 = 마감 15분 전 */
	private static final ZonedDateTime BEFORE_CLOSE = ZonedDateTime.of(2026, 11, 10, 15, 45, 0, 0, NY);

	private final IndexEtfSkill skill = new IndexEtfSkill();

	private MarketDataService marketData;
	private AgentTradingService trading;
	private TradePlanner planner;
	private TradingCalendar calendar;
	private MemberAccount account;
	private AgentTask task;

	@BeforeEach
	void setUp() {
		marketData = mock(MarketDataService.class);
		trading = mock(AgentTradingService.class);
		planner = mock(TradePlanner.class);
		calendar = mock(TradingCalendar.class);
		account = mock(MemberAccount.class);
		task = new AgentTask(1L, 2L, 3L, IndexEtfSkill.ID, "t", "i", "{}", "{}", null, true, null, null, null, 0, AgentTask.CreatedBy.USER);
		when(calendar.isTradingDay(any(), any())).thenReturn(true);
		when(trading.reconcile(any(), any())).thenReturn(List.of());
		when(marketData.resolve("SPY", null)).thenReturn(new StockSummary("SPY", "SPDR S&P 500 ETF", null, Market.AMEX, Currency.USD));
	}

	// ─────────────────────────── 설정 ───────────────────────────

	@Test
	void defaultsAreSpyUsdBoth() {
		Map<String, Object> cfg = skill.normalize(Map.of());
		assertThat(cfg).containsEntry("etf", "SPY").containsEntry("market", "US").containsEntry("mode", "BOTH")
			.containsEntry("trendDays", 150).containsEntry("targetVolPct", 15.0).containsEntry("bandPct", 10.0)
			.containsEntry("maxLossPct", 25.0);
		assertThat(cfg.get("budget")).isEqualTo(3000.0);
		Limits l = skill.limits(cfg);
		assertThat(l.currency()).isEqualTo("USD");
		assertThat(l.positionCap()).isEqualByComparingTo(l.budget());
		assertThat(l.lossLimitAmount()).isEqualByComparingTo("750");
		assertThat(skill.schedule(cfg).describe()).contains("미국장").contains("마감 15분 전");
		assertThat(skill.title(cfg)).contains("SPY").contains("3,000.00달러");
	}

	@Test
	void koreanEtfUsesWonAndKoreanSchedule() {
		Map<String, Object> cfg = skill.normalize(Map.of("etf", "069500"));
		assertThat(cfg).containsEntry("market", "KRX");
		assertThat(cfg.get("budget")).isEqualTo(5_000_000.0);
		Limits l = skill.limits(cfg);
		assertThat(l.currency()).isEqualTo("KRW");
		assertThat(skill.schedule(cfg).describe()).contains("한국장").contains("마감 15분 전");
	}

	@Test
	void rejectsUnknownEtfModeAndOutOfRangeValues() {
		assertThatThrownBy(() -> skill.normalize(Map.of("etf", "TQQQ"))).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> skill.normalize(Map.of("mode", "YOLO"))).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> skill.normalize(Map.of("trendDays", 10))).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> skill.normalize(Map.of("targetVolPct", 80))).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> skill.normalize(Map.of("budget", "abc"))).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> skill.normalize(Map.of("maxLossPct", 90))).isInstanceOf(ApiException.class);
	}

	@Test
	void etfCannotBeSwitchedWhileHolding() {
		Map<String, Object> spy = skill.normalize(Map.of());
		Map<String, Object> kodex = skill.normalize(Map.of("etf", "069500"));
		assertThatThrownBy(() -> skill.validateChange(spy, kodex, true)).isInstanceOf(ApiException.class).hasMessageContaining("보유 중");
		// 보유가 없으면 바꿀 수 있고, 같은 ETF 의 다른 설정 변경은 보유 중에도 가능하다
		skill.validateChange(spy, kodex, false);
		skill.validateChange(spy, skill.normalize(Map.of("trendDays", 200)), true);
	}

	// ─────────────────────────── 실행 ───────────────────────────

	/** 약간의 등락이 있는 일봉 200개. 어제까지. drift 가 양수면 우상향, 음수면 우하향. */
	private List<Candle> candles(double start, double drift) {
		List<Candle> list = new ArrayList<>();
		LocalDate d = LocalDate.of(2026, 1, 1);
		double c = start;
		for (int i = 0; i < 200; i++) {
			c = c * (1 + drift) * (i % 2 == 0 ? 1.003 : 0.997);
			list.add(new Candle(d.plusDays(i).toString(), BigDecimal.valueOf(c), BigDecimal.valueOf(c), BigDecimal.valueOf(c),
				BigDecimal.valueOf(c), 1000));
		}
		return list;
	}

	private void market(double price, List<Candle> history) {
		when(marketData.quote(any(), eq(Market.AMEX), eq("SPY"))).thenReturn(new Quote("SPY", "SPDR S&P 500 ETF", Market.AMEX,
			Currency.USD, BigDecimal.valueOf(price), BigDecimal.ZERO, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0,
			OffsetDateTime.now()));
		when(marketData.candles(any(), eq(Market.AMEX), eq("SPY"), eq(Period.D), anyInt())).thenReturn(history);
	}

	private static double lastClose(List<Candle> history) {
		return history.get(history.size() - 1).close().doubleValue();
	}

	private AgentPosition holding(long qty, double price) {
		return new AgentPosition(1L, 2L, 3L, Market.AMEX, "SPY", "SPDR S&P 500 ETF", qty, BigDecimal.valueOf(price));
	}

	private void ledger(AgentPosition... open) {
		Limits limits = skill.limits(skill.normalize(Map.of()));
		BigDecimal invested = BigDecimal.ZERO;
		BigDecimal unrealized = BigDecimal.ZERO;
		for (AgentPosition p : open) {
			invested = invested.add(p.cost());
			unrealized = unrealized.add(p.unrealizedPnl());
		}
		LedgerView view = new LedgerView(limits, BigDecimal.ZERO, unrealized, invested, limits.budget().subtract(invested),
			BigDecimal.ZERO, List.of(open));
		when(trading.view(any())).thenReturn(view);
	}

	private void orders() {
		when(planner.plan(any(), eq("SPY"), eq("AMEX"), any(), any(), any(), any())).thenAnswer(inv -> new Plan(Market.AMEX, "SPY",
			"SPDR S&P 500 ETF", inv.getArgument(3), (Long)inv.getArgument(4), null, BigDecimal.valueOf(100), "지정가"));
		OrderOutcome ok = new OrderOutcome(true, "접수", "1", "BUY", "SPY", "SPDR S&P 500 ETF", Market.AMEX, Currency.USD, 1, BigDecimal.ONE);
		when(trading.buy(any(), any(), any(), any())).thenReturn(ok);
		when(trading.sell(any(), any(), any(), any())).thenReturn(ok);
	}

	private SkillContext ctx(Map<String, Object> config, Map<String, Object> state, boolean manual) {
		return new SkillContext(task, account, skill.normalize(config), state, manual,
			new SkillContext.Services(trading, marketData, null, planner, calendar));
	}

	@Test
	void buysWhenFlatAndInAnUptrend() {
		List<Candle> h = candles(100, 0.001);
		double price = lastClose(h) * 1.001;
		market(price, h);
		ledger();
		orders();
		SkillContext c = ctx(Map.of(), new LinkedHashMap<>(), false);
		skill.runAt(c, BEFORE_CLOSE);

		long expected = (long)Math.floor(3000 / price);      // 변동성이 낮아 목표 비중 100%
		ArgumentCaptor<Long> qty = ArgumentCaptor.forClass(Long.class);
		verify(planner).plan(any(), eq("SPY"), eq("AMEX"), eq("BUY"), qty.capture(), any(), any());
		assertThat(qty.getValue()).isEqualTo(expected);
		verify(trading).buy(any(), any(), any(), any());
		verify(trading, never()).sell(any(), any(), any(), any());
		assertThat(c.events().get(0)).contains("상승 추세").contains("목표 비중 100%").contains(expected + "주 매수 접수");
		assertThat(c.state()).containsEntry("trendOn", true).containsKey("volWeight");
	}

	@Test
	void sellsEverythingWhenTheTrendTurnsDown() {
		List<Candle> h = candles(100, -0.001);
		market(lastClose(h) * 0.999, h);
		ledger(holding(23, 130));
		orders();
		Map<String, Object> state = new LinkedHashMap<>(Map.of("trendOn", true));
		SkillContext c = ctx(Map.of(), state, false);
		skill.runAt(c, BEFORE_CLOSE);

		ArgumentCaptor<Long> qty = ArgumentCaptor.forClass(Long.class);
		verify(planner).plan(any(), eq("SPY"), eq("AMEX"), eq("SELL"), qty.capture(), any(), any());
		assertThat(qty.getValue()).isEqualTo(23L);
		ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
		verify(trading).sell(any(), any(), any(), reason.capture());
		assertThat(reason.getValue()).contains("하락 추세");
		assertThat(c.events().get(0)).contains("하락 추세").contains("목표 비중 0%").contains("23주 매도 접수");
		assertThat(c.state()).containsEntry("trendOn", false);
	}

	@Test
	void doesNothingWhenTheHoldingAlreadyMatchesTheTarget() {
		List<Candle> h = candles(100, 0.001);
		double price = lastClose(h) * 1.001;
		market(price, h);
		ledger(holding((long)Math.floor(3000 / price) - 1, price));   // 목표보다 1주 적게 들고 있음(차이 4%p < 허용폭 10%p)
		orders();
		SkillContext c = ctx(Map.of(), new LinkedHashMap<>(Map.of("trendOn", true, "volWeight", 1.0, "volMonth", "2026-11")), false);
		skill.runAt(c, BEFORE_CLOSE);

		verify(trading, never()).buy(any(), any(), any(), any());
		verify(trading, never()).sell(any(), any(), any(), any());
		assertThat(c.events()).isEmpty();            // 조용히 지나간다(알림을 만들지 않음)
	}

	@Test
	void manualRunAlwaysExplainsItself() {
		List<Candle> h = candles(100, 0.001);
		double price = lastClose(h) * 1.001;
		market(price, h);
		ledger(holding((long)Math.floor(3000 / price), price));
		orders();
		SkillContext c = ctx(Map.of(), new LinkedHashMap<>(Map.of("trendOn", true, "volWeight", 1.0, "volMonth", "2026-11")), true);
		skill.runAt(c, BEFORE_CLOSE);
		assertThat(c.events()).hasSize(1);
		assertThat(c.events().get(0)).contains("변동 없음").contains("목표 비중 100%").contains("현재 98%");   // 정수 주식 수라서 목표보다 조금 모자란다
	}

	@Test
	void doesNothingOutsideMarketHours() {
		List<Candle> h0 = candles(100, 0.001);
		market(lastClose(h0), h0);
		ledger();
		orders();
		ZonedDateTime saturday = ZonedDateTime.of(2026, 11, 14, 15, 45, 0, 0, NY);
		SkillContext c = ctx(Map.of(), new LinkedHashMap<>(), false);
		skill.runAt(c, saturday);
		verify(trading, never()).buy(any(), any(), any(), any());
		assertThat(c.events()).isEmpty();
		SkillContext manual = ctx(Map.of(), new LinkedHashMap<>(), true);
		skill.runAt(manual, saturday);
		assertThat(manual.events().get(0)).contains("장이 열려 있지 않아");
	}

	@Test
	void notEnoughHistoryMeansNoDecision() {
		List<Candle> h = candles(100, 0.001).subList(0, 50);
		market(lastClose(h), h);
		ledger(holding(23, 130));
		orders();
		SkillContext c = ctx(Map.of(), new LinkedHashMap<>(), false);
		skill.runAt(c, BEFORE_CLOSE);
		verify(trading, never()).sell(any(), any(), any(), any());
		assertThat(c.events().get(0)).contains("가격 이력이 부족");
	}

	@Test
	void todaysPartialBarIsReplacedByTheCurrentPrice() {
		List<Candle> history = new ArrayList<>(candles(100, 0.001));
		double price = lastClose(history) * 1.001;
		// 오늘(2026-11-10) 봉이 이미 들어 있고 값이 엉뚱해도 쓰지 않는다
		history.add(new Candle("2026-11-10", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, 1));
		market(price, history);
		ledger();
		orders();
		SkillContext c = ctx(Map.of(), new LinkedHashMap<>(), false);
		skill.runAt(c, BEFORE_CLOSE);
		assertThat(c.events().get(0)).contains("상승 추세").contains((long)Math.floor(3000 / price) + "주 매수 접수");
	}

	@Test
	void liquidatesAndPausesWhenTheLossLimitIsHit() {
		List<Candle> h = candles(100, -0.001);
		market(70, h);
		Limits limits = skill.limits(skill.normalize(Map.of()));
		AgentPosition p = holding(23, 130);
		p.mark(BigDecimal.valueOf(70));                // 평가손실 23 * 60 = 1380 > 750(시드의 25%)
		when(trading.view(any())).thenReturn(new LedgerView(limits, BigDecimal.ZERO, p.unrealizedPnl(), p.cost(),
			BigDecimal.ZERO, BigDecimal.ZERO, List.of(p)));
		when(trading.liquidate(any(), any(), any())).thenReturn(List.of());
		SkillContext c = ctx(Map.of(), new LinkedHashMap<>(), false);
		skill.runAt(c, BEFORE_CLOSE);
		verify(trading).liquidate(any(), any(), any());
		assertThat(c.pauseReason()).contains("누적 손실");
		verify(trading, never()).buy(any(), any(), any(), any());
	}
}
