package com.backend.domains.agent.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.backend.common.exception.ApiException;
import com.backend.domains.agent.AgentTask;
import com.backend.domains.agent.AgentTradingService;
import com.backend.domains.agent.AgentTradingService.LedgerView;
import com.backend.domains.agent.Limits;
import com.backend.domains.factor.FactorSnapshot;
import com.backend.domains.factor.FactorSnapshotRepository;
import com.backend.domains.member.domain.MemberAccount;
import com.backend.domains.trade.TradePlanner;
import com.backend.domains.trade.TradePlanner.Plan;
import com.backend.market.Currency;
import com.backend.market.FactorDataService;
import com.backend.market.FactorDataService.FlowDay;
import com.backend.market.FactorDataService.Valuation;
import com.backend.market.Market;
import com.backend.market.MarketDataService;
import com.backend.market.PredictionService;
import com.backend.market.TradingCalendar;
import com.backend.market.dto.MarketDtos.OrderOutcome;
import com.backend.market.dto.MarketDtos.Recommendation;

class MultiFactorSkillTest {

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
	/** 2026-11-04(수) 10:30 KST: 장중 */
	private static final ZonedDateTime OPEN = ZonedDateTime.of(2026, 11, 4, 10, 30, 0, 0, SEOUL);
	private static final LocalDate TODAY = OPEN.toLocalDate();

	private FactorDataService factors;
	private FactorSnapshotRepository snapshots;
	private MultiFactorSkill skill;
	private AgentTradingService trading;
	private PredictionService prediction;
	private TradePlanner planner;
	private TradingCalendar calendar;
	private MemberAccount account;
	private AgentTask task;

	@BeforeEach
	void setUp() {
		factors = mock(FactorDataService.class);
		snapshots = mock(FactorSnapshotRepository.class);
		skill = new MultiFactorSkill(factors, snapshots);
		trading = mock(AgentTradingService.class);
		prediction = mock(PredictionService.class);
		planner = mock(TradePlanner.class);
		calendar = mock(TradingCalendar.class);
		account = mock(MemberAccount.class);
		task = mock(AgentTask.class);
		when(calendar.isTradingDay(Market.KRX, TODAY)).thenReturn(true);
		when(account.isPaper()).thenReturn(true);
	}

	// ─────────────────────────── 설정 ───────────────────────────

	@Test
	void normalizeFillsDefaultsAndAcceptsStringNumbers() {
		Map<String, Object> c = skill.normalize(Map.of("budget", "7,000,000", "maxPositions", 4));
		assertThat(c.get("market")).isEqualTo("KRX");
		assertThat(((Number)c.get("budget")).doubleValue()).isEqualTo(7_000_000);
		assertThat(c.get("maxPositions")).isEqualTo(4);
		assertThat(c.get("requireFlow")).isEqualTo("OFF");
		assertThat(c.get("excludeLoss")).isEqualTo("ON");
		assertThat(((Number)c.get("wTech")).doubleValue()).isEqualTo(60);
	}

	@Test
	void normalizeRejectsOutOfRangeValues() {
		assertThatThrownBy(() -> skill.normalize(Map.of("budget", 10))).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> skill.normalize(Map.of("maxPositions", 0))).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> skill.normalize(Map.of("stopLossPct", 99))).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> skill.normalize(Map.of("budget", "abc"))).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> skill.normalize(Map.of("requireFlow", "MAYBE"))).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> skill.normalize(Map.of("wTech", 0, "wFlow", 0, "wValue", 0))).isInstanceOf(ApiException.class);
	}

	@Test
	void limitsComeFromBudgetPositionsAndLossPct() {
		Limits l = skill.limits(skill.normalize(Map.of("budget", 6_000_000, "maxPositions", 4, "maxLossPct", 8)));
		assertThat(l.budget()).isEqualByComparingTo("6000000");
		assertThat(l.currency()).isEqualTo("KRW");
		assertThat(l.positionCap()).isEqualByComparingTo("1500000");
		assertThat(l.lossLimitAmount()).isEqualByComparingTo("480000");
	}

	@Test
	void everyDeclaredParamHasADefaultThatPassesNormalize() {
		Map<String, Object> defaults = new HashMap<>();
		skill.params().forEach(p -> defaults.put(p.key(), p.defaultValue()));
		Map<String, Object> c = skill.normalize(defaults);
		skill.params().forEach(p -> assertThat(c).containsKey(p.key()));
	}

	// ─────────────────────────── 실행 ───────────────────────────

	private SkillContext ctx(Map<String, Object> overrides, boolean manual) {
		Map<String, Object> raw = new HashMap<>(overrides);
		Map<String, Object> config = skill.normalize(raw);
		var services = new SkillContext.Services(trading, mock(MarketDataService.class), prediction, planner, calendar);
		return new SkillContext(task, account, config, new HashMap<>(), manual, services);
	}

	private void ledger(Map<String, Object> config, double available) {
		Limits limits = skill.limits(config);
		when(trading.view(task)).thenReturn(new LedgerView(limits, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
			BigDecimal.valueOf(available), BigDecimal.ZERO, List.of()));
		when(trading.reconcile(task, account)).thenReturn(List.of());
	}

	private static Recommendation rec(String code, String name, double score) {
		return new Recommendation(code, name, Market.KRX, Currency.KRW, BigDecimal.valueOf(50_000), score, 0.5, "UP", 55,
			List.of(), "2026-11-03");
	}

	private static List<FlowDay> buying() {
		return List.of(new FlowDay(LocalDate.of(2026, 11, 3), 120, 90, -200, 3000), new FlowDay(LocalDate.of(2026, 11, 2), 100, 80, -170, 3000));
	}

	private static List<FlowDay> selling() {
		return List.of(new FlowDay(LocalDate.of(2026, 11, 3), -120, -90, 200, 3000), new FlowDay(LocalDate.of(2026, 11, 2), -100, -80, 170, 3000));
	}

	private void stubOrderPath() {
		when(planner.plan(any(), anyString(), anyString(), eq("BUY"), isNull(), any(BigDecimal.class), isNull()))
			.thenAnswer(inv -> new Plan(Market.KRX, inv.getArgument(1), "N-" + inv.getArgument(1), "BUY", 10, null,
				BigDecimal.valueOf(50_000), "시장가"));
		when(trading.buy(any(), any(), any(Plan.class), anyString())).thenAnswer(inv -> {
			Plan p = inv.getArgument(2);
			return new OrderOutcome(true, "ok", "0001", "BUY", p.symbol(), p.name(), Market.KRX, Currency.KRW, p.quantity(), p.refPrice());
		});
	}

	@Test
	void buysOnlyCandidatesAboveTheScoreThresholdBestFirstAndRecordsAllOfThem() {
		SkillContext ctx = ctx(Map.of("budget", 5_000_000, "maxPositions", 2), false);
		ledger(skill.normalize(Map.of("budget", 5_000_000, "maxPositions", 2)), 5_000_000);
		when(factors.indexChangePct(account, "0001")).thenReturn(-0.4);
		when(prediction.modelPicks(account, "KRX", 15)).thenReturn(List.of(
			rec("000001", "약한종목", 12), rec("000002", "좋은종목", 40), rec("000003", "적자종목", 45)));
		when(factors.flows(account, "000001", TODAY)).thenReturn(selling());
		when(factors.flows(account, "000002", TODAY)).thenReturn(buying());
		when(factors.flows(account, "000003", TODAY)).thenReturn(buying());
		when(factors.valuation(account, "000001")).thenReturn(new Valuation(60.0, 4.0, 100.0, 1000.0));
		when(factors.valuation(account, "000002")).thenReturn(new Valuation(8.0, 0.8, 1000.0, 10000.0));
		when(factors.valuation(account, "000003")).thenReturn(new Valuation(0.0, 1.2, -300.0, 5000.0));
		stubOrderPath();

		skill.run(ctx, OPEN);

		ArgumentCaptor<Plan> plan = ArgumentCaptor.forClass(Plan.class);
		verify(trading, times(1)).buy(any(), any(), plan.capture(), anyString());
		assertThat(plan.getValue().symbol()).isEqualTo("000002");
		assertThat(ctx.outcomes()).hasSize(1);
		assertThat(ctx.events()).anyMatch(e -> e.contains("좋은종목") || e.contains("N-000002"));
		assertThat(ctx.state()).containsEntry("buyDate", TODAY.toString());

		ArgumentCaptor<FactorSnapshot> snap = ArgumentCaptor.forClass(FactorSnapshot.class);
		verify(snapshots, times(3)).save(snap.capture());
		assertThat(snap.getAllValues()).filteredOn(FactorSnapshot::isPicked).extracting(FactorSnapshot::getSymbol).containsExactly("000002");
	}

	@Test
	void doesNotBuyTwiceInTheSameDay() {
		SkillContext ctx = ctx(Map.of(), false);
		ctx.state().put("buyDate", TODAY.toString());
		ctx.state().put("reconciledDate", TODAY.toString());
		ledger(ctx.task() == null ? Map.of() : skill.normalize(Map.of()), 5_000_000);

		skill.run(ctx, OPEN);

		verify(prediction, never()).modelPicks(any(), anyString(), anyInt());
		verify(trading, never()).buy(any(), any(), any(), anyString());
	}

	@Test
	void marketPanicSkipsBuyingButKeepsTheDayOpenForRetry() {
		SkillContext ctx = ctx(Map.of("marketGuardPct", 2.0), false);
		ledger(skill.normalize(Map.of()), 5_000_000);
		when(factors.indexChangePct(account, "0001")).thenReturn(-3.1);

		skill.run(ctx, OPEN);

		verify(prediction, never()).modelPicks(any(), anyString(), anyInt());
		verify(trading, never()).buy(any(), any(), any(), anyString());
		assertThat(ctx.events()).anyMatch(e -> e.contains("급락"));
		assertThat(ctx.state()).doesNotContainKey("buyDate");
	}

	@Test
	void realAccountFailsClosedWhenTheIndexCannotBeRead() {
		when(account.isPaper()).thenReturn(false);
		SkillContext ctx = ctx(Map.of("marketGuardPct", 2.0), false);
		ledger(skill.normalize(Map.of()), 5_000_000);
		when(factors.indexChangePct(account, "0001")).thenThrow(new IllegalStateException("boom"));

		skill.run(ctx, OPEN);

		verify(trading, never()).buy(any(), any(), any(), anyString());
		assertThat(ctx.events()).anyMatch(e -> e.contains("안전을 위해"));
	}

	@Test
	void paperAccountKeepsTradingWhenTheIndexCannotBeReadAndFlowIsMissing() {
		SkillContext ctx = ctx(Map.of("marketGuardPct", 2.0), false);
		ledger(skill.normalize(Map.of()), 5_000_000);
		when(factors.indexChangePct(account, "0001")).thenThrow(new IllegalStateException("모의투자 미지원"));
		when(prediction.modelPicks(account, "KRX", 15)).thenReturn(List.of(rec("000002", "기술강한종목", 50)));
		when(factors.flows(account, "000002", TODAY)).thenThrow(new IllegalStateException("모의투자 미지원"));
		when(factors.valuation(account, "000002")).thenThrow(new IllegalStateException("모의투자 미지원"));
		stubOrderPath();

		skill.run(ctx, OPEN);

		// 기술 50점 만점 × 비중 0.6 = 60점 ≥ 기준 20점
		verify(trading, times(1)).buy(any(), any(), any(Plan.class), anyString());
		assertThat(ctx.events()).anyMatch(e -> e.contains("수급 정보를 가져오지 못했습니다"));
	}

	@Test
	void requireFlowBlocksEverythingWhenFlowIsUnavailable() {
		SkillContext ctx = ctx(Map.of("requireFlow", "ON", "marketGuardPct", 0), false);
		ledger(skill.normalize(Map.of()), 5_000_000);
		when(prediction.modelPicks(account, "KRX", 15)).thenReturn(List.of(rec("000002", "기술강한종목", 50)));
		when(factors.flows(account, "000002", TODAY)).thenThrow(new IllegalStateException("모의투자 미지원"));
		when(factors.valuation(account, "000002")).thenReturn(new Valuation(8.0, 0.8, 1.0, 1.0));

		skill.run(ctx, OPEN);

		verify(trading, never()).buy(any(), any(), any(), anyString());
		assertThat(ctx.events()).anyMatch(e -> e.contains("기준") && e.contains("만족하는 종목이 없어"));
	}

	@Test
	void nothingHappensWhenTheMarketIsClosed() {
		ZonedDateTime night = ZonedDateTime.of(2026, 11, 4, 20, 0, 0, 0, SEOUL);
		SkillContext ctx = ctx(Map.of(), false);

		skill.run(ctx, night);

		assertThat(ctx.events()).isEmpty();
		verify(prediction, never()).modelPicks(any(), anyString(), anyInt());
	}

	@Test
	void manualRunExplainsWhyNothingHappenedWhenClosed() {
		SkillContext ctx = ctx(Map.of(), true);
		skill.run(ctx, ZonedDateTime.of(2026, 11, 4, 20, 0, 0, 0, SEOUL));
		assertThat(ctx.events()).anyMatch(e -> e.contains("장이 열려 있지 않아"));
	}

	@Test
	void noBudgetLeftMeansNoBuying() {
		SkillContext ctx = ctx(Map.of("marketGuardPct", 0), false);
		ledger(skill.normalize(Map.of()), 0);

		skill.run(ctx, OPEN);

		verify(prediction, never()).modelPicks(any(), anyString(), anyInt());
		assertThat(ctx.events()).anyMatch(e -> e.contains("남은 예산이 없어"));
	}
}
