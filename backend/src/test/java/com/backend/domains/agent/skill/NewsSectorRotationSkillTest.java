package com.backend.domains.agent.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.backend.common.exception.ApiException;
import com.backend.common.exception.ErrorCode;
import com.backend.domains.agent.AgentPosition;
import com.backend.domains.agent.AgentTask;
import com.backend.domains.agent.AgentTradingService;
import com.backend.domains.agent.AgentTradingService.LedgerView;
import com.backend.domains.agent.Limits;
import com.backend.domains.chat.ai.LlmGateway;
import com.backend.domains.member.domain.MemberAccount;
import com.backend.domains.news.WikiNewsService;
import com.backend.domains.trade.TradePlanner;
import com.backend.domains.trade.TradePlanner.Plan;
import com.backend.market.Currency;
import com.backend.market.Market;
import com.backend.market.MarketDataService;
import com.backend.market.TradingCalendar;
import com.backend.market.dto.MarketDtos.OrderOutcome;
import com.backend.market.dto.MarketDtos.StockSummary;

class NewsSectorRotationSkillTest {

	private static final ZoneId NY = ZoneId.of("America/New_York");
	/** 2026-11-03(화) 10:00 뉴욕 = 11월 첫 거래일 정도의 월 초, 미국 장 중 */
	private static final ZonedDateTime EARLY_NOV = ZonedDateTime.of(2026, 11, 3, 10, 0, 0, 0, NY);
	private static final ZonedDateTime LATE_NOV = ZonedDateTime.of(2026, 11, 17, 10, 0, 0, 0, NY);
	private static final List<String> US = List.of("XLK", "XLE", "XLF", "XLV", "XLY", "XLP", "XLI", "XLU", "XLB", "XLRE", "XLC");

	private WikiNewsService wiki;
	private LlmGateway llm;
	private NewsSectorRotationSkill skill;
	private MarketDataService marketData;
	private AgentTradingService trading;
	private TradePlanner planner;
	private TradingCalendar calendar;
	private MemberAccount account;
	private AgentTask task;

	@BeforeEach
	void setUp() {
		wiki = mock(WikiNewsService.class);
		llm = mock(LlmGateway.class);
		skill = new NewsSectorRotationSkill(wiki, llm);
		skill.llmTimeoutSeconds = 5;
		marketData = mock(MarketDataService.class);
		trading = mock(AgentTradingService.class);
		planner = mock(TradePlanner.class);
		calendar = mock(TradingCalendar.class);
		account = mock(MemberAccount.class);
		task = new AgentTask(1L, 2L, 3L, NewsSectorRotationSkill.ID, "t", "i", "{}", "{}", null, true, null, null, null, 0, AgentTask.CreatedBy.USER);
		when(calendar.isTradingDay(any(), any())).thenReturn(true);
		when(trading.reconcile(any(), any())).thenReturn(List.of());
		when(wiki.monthlyDigest(any(YearMonth.class))).thenReturn("## Business and economy\n10-05 Oil prices surge after supply cut");
		doAnswer(inv -> {
			String s = inv.getArgument(0);
			if (!US.contains(s)) {
				throw ApiException.of(ErrorCode.STOCK_NOT_FOUND, "없음: " + s);
			}
			return new StockSummary(s, s + " ETF", s + " Select Sector SPDR", s.equals("XLE") ? Market.AMEX : Market.AMEX, Currency.USD);
		}).when(marketData).resolve(anyString(), any());
		when(planner.plan(any(), anyString(), any(), any(), any(), any(), any())).thenAnswer(inv -> new Plan(Market.AMEX, inv.getArgument(1),
			"Name " + inv.getArgument(1), inv.getArgument(3), 5L, null, BigDecimal.valueOf(100), "지정가"));
		OrderOutcome ok = new OrderOutcome(true, "접수", "1", "BUY", "XLE", "n", Market.AMEX, Currency.USD, 1, BigDecimal.ONE);
		when(trading.buy(any(), any(), any(), any())).thenReturn(ok);
		when(trading.sell(any(), any(), any(), any())).thenReturn(ok);
		ledger();
	}

	private Limits limits() {
		return skill.limits(skill.normalize(Map.of("market", "US")));
	}

	private LedgerView view(AgentPosition... open) {
		Limits limits = limits();
		BigDecimal invested = BigDecimal.ZERO;
		for (AgentPosition p : open) {
			invested = invested.add(p.cost());
		}
		return new LedgerView(limits, BigDecimal.ZERO, BigDecimal.ZERO, invested, limits.budget().subtract(invested), BigDecimal.ZERO, List.of(open));
	}

	private void ledger(AgentPosition... open) {
		when(trading.view(any())).thenReturn(view(open));
	}

	private SkillContext ctx(Map<String, Object> config, Map<String, Object> state, boolean manual) {
		return new SkillContext(task, account, skill.normalize(config), state, manual,
			new SkillContext.Services(trading, marketData, null, planner, calendar));
	}

	private void aiSays(String json) {
		when(llm.complete(anyString(), anyString())).thenReturn(json);
	}

	private static String ranking(String... codes) {
		return "{\"ranking\":[\"" + String.join("\",\"", codes) + "\"],\"rationale\":\"에너지 강세 예상\"}";
	}

	// ─────────────────────────── 설정 ───────────────────────────

	@Test
	void defaultsFollowThePastTestAndNormalizeRejectsBadValues() {
		Map<String, Object> us = skill.normalize(Map.of("market", "US"));
		assertThat(us).containsEntry("mode", "TRADE").containsEntry("topN", 3).containsEntry("maxLossPct", 25.0);
		assertThat(us.get("budget")).isEqualTo(3000.0);
		Limits l = skill.limits(us);
		assertThat(l.currency()).isEqualTo("USD");
		assertThat(l.lossLimitAmount()).isEqualByComparingTo("750");
		assertThat(skill.schedule(us).describe()).contains("미국장");
		assertThat(skill.title(us)).contains("미국");
		assertThat(skill.normalize(Map.of("market", "KRX")).get("budget")).isEqualTo(5_000_000.0);
		assertThat(skill.trades()).isTrue();
		assertThat(skill.detail()).contains("한국").contains("미국").contains("우연");
		assertThatThrownBy(() -> skill.normalize(Map.of("market", "JP"))).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> skill.normalize(Map.of("mode", "YOLO"))).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> skill.normalize(Map.of("topN", 9))).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> skill.normalize(Map.of("market", "US", "budget", 10))).isInstanceOf(ApiException.class);
		Map<String, Object> defaults = new LinkedHashMap<>();
		skill.params().forEach(p -> {
			if (p.defaultValue() != null) {
				defaults.put(p.key(), p.defaultValue());
			}
		});
		assertThat(skill.normalize(defaults)).containsEntry("market", "US");
	}

	// ─────────────────────────── 한 달 흐름 ───────────────────────────

	@Test
	void ranksSectorsFromLastMonthsNewsAndBuysTheTopThreeEqually() {
		aiSays(ranking("XLE", "XLK", "XLF", "XLV", "XLY", "XLP", "XLI", "XLU", "XLB", "XLRE", "XLC"));
		Map<String, Object> state = new LinkedHashMap<>();
		SkillContext c = ctx(Map.of("market", "US"), state, false);
		skill.run(c, EARLY_NOV);

		verify(wiki).monthlyDigest(YearMonth.of(2026, 10));                                  // 11월 판단에는 10월 뉴스
		ArgumentCaptor<BigDecimal> amount = ArgumentCaptor.forClass(BigDecimal.class);
		verify(planner).plan(any(), eq("XLE"), eq("AMEX"), eq("BUY"), any(), amount.capture(), any());
		verify(planner).plan(any(), eq("XLK"), eq("AMEX"), eq("BUY"), any(), any(), any());
		verify(planner).plan(any(), eq("XLF"), eq("AMEX"), eq("BUY"), any(), any(), any());
		verify(planner, never()).plan(any(), eq("XLV"), any(), any(), any(), any(), any());
		assertThat(amount.getValue()).isCloseTo(BigDecimal.valueOf(1000), org.assertj.core.api.Assertions.within(new BigDecimal("0.01")));
		verify(trading, times(3)).buy(any(), any(), any(), any());
		assertThat(c.events()).anyMatch(e -> e.contains("2026-10 뉴스로 산업 순위") && e.contains("XLE") && e.contains("에너지 강세 예상"));
		assertThat(state).containsEntry("rebalancedMonth", "2026-11");
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> log = (List<Map<String, Object>>)state.get("rankingLog");
		assertThat(log).hasSize(1);
		assertThat(log.get(0)).containsEntry("month", "2026-11").containsEntry("targets", List.of("XLE", "XLK", "XLF"));

		SkillContext again = ctx(Map.of("market", "US"), state, true);
		skill.run(again, EARLY_NOV.plusDays(1));
		verify(llm, times(1)).complete(anyString(), anyString());                              // 한 달에 AI 호출은 한 번
		assertThat(again.events()).anyMatch(e -> e.contains("이미 산업 순위를 매겼습니다"));
	}

	@Test
	void sellsEtfsThatDroppedOutOfTheTopAndKeepsTheOnesStillInIt() {
		AgentPosition xle = new AgentPosition(1L, 2L, 3L, Market.AMEX, "XLE", "Energy", 10, BigDecimal.valueOf(100));
		AgentPosition xlk = new AgentPosition(1L, 2L, 3L, Market.AMEX, "XLK", "Tech", 10, BigDecimal.valueOf(100));
		when(trading.view(any())).thenReturn(view(xle, xlk), view(xle, xlk), view(xlk));
		aiSays(ranking("XLK", "XLF", "XLV", "XLE", "XLY", "XLP", "XLI", "XLU", "XLB", "XLRE", "XLC"));
		SkillContext c = ctx(Map.of("market", "US"), new LinkedHashMap<>(), false);
		skill.run(c, EARLY_NOV);

		verify(planner).plan(any(), eq("XLE"), eq("AMEX"), eq("SELL"), any(), any(), any());
		verify(planner, never()).plan(any(), eq("XLK"), any(), any(), any(), any(), any());          // 이미 보유 중인 상위 ETF 는 그대로
		verify(planner).plan(any(), eq("XLF"), eq("AMEX"), eq("BUY"), any(), any(), any());
		verify(planner).plan(any(), eq("XLV"), eq("AMEX"), eq("BUY"), any(), any(), any());
		assertThat(c.events()).anyMatch(e -> e.contains("매도 접수") && e.contains("상위 3개에서 빠짐"));
	}

	@Test
	void recordModeOnlyLogsTheRanking() {
		aiSays(ranking("XLE", "XLK", "XLF", "XLV"));
		Map<String, Object> state = new LinkedHashMap<>();
		SkillContext c = ctx(Map.of("market", "US", "mode", "RECORD"), state, false);
		skill.run(c, EARLY_NOV);
		verify(trading, never()).buy(any(), any(), any(), any());
		verify(trading, never()).sell(any(), any(), any(), any());
		verify(trading, never()).view(any());
		assertThat(c.events()).anyMatch(e -> e.contains("[기록만]"));
		assertThat(state).containsKey("rankingLog");
	}

	@Test
	void unknownAndDuplicateCodesAreDroppedFromTheRanking() {
		aiSays(ranking("XLE", "XLE", "AAPL", "XLK", "XLF", "XLV"));
		SkillContext c = ctx(Map.of("market", "US", "topN", 2), new LinkedHashMap<>(), false);
		skill.run(c, EARLY_NOV);
		verify(planner).plan(any(), eq("XLE"), eq("AMEX"), eq("BUY"), any(), any(), any());
		verify(planner).plan(any(), eq("XLK"), eq("AMEX"), eq("BUY"), any(), any(), any());
		verify(planner, never()).plan(any(), eq("AAPL"), any(), any(), any(), any(), any());
		verify(planner, never()).plan(any(), eq("XLF"), any(), any(), any(), any(), any());
	}

	// ─────────────────────────── 실패·건너뛰기 ───────────────────────────

	@Test
	void failedNewsOrAnswerLeavesTheMonthOpenForARetryAndStopsAfterThreeTries() {
		when(wiki.monthlyDigest(any(YearMonth.class))).thenReturn(null);
		Map<String, Object> state = new LinkedHashMap<>();
		for (int i = 0; i < 4; i++) {
			skill.run(ctx(Map.of("market", "US"), state, false), EARLY_NOV);
		}
		verify(wiki, times(3)).monthlyDigest(any(YearMonth.class));
		verify(llm, never()).complete(anyString(), anyString());
		assertThat(state).doesNotContainKey("rebalancedMonth");

		when(wiki.monthlyDigest(any(YearMonth.class))).thenReturn("digest");
		aiSays("죄송합니다, 순위를 매길 수 없습니다.");
		Map<String, Object> state2 = new LinkedHashMap<>();
		SkillContext c = ctx(Map.of("market", "US"), state2, false);
		skill.run(c, EARLY_NOV);
		verify(trading, never()).buy(any(), any(), any(), any());
		assertThat(state2).doesNotContainKey("rebalancedMonth");
		assertThat(c.events()).anyMatch(e -> e.contains("순위를 충분히 읽지 못했습니다"));
	}

	@Test
	void aMonthNotRankedByTheTenthIsSkippedInsteadOfTradingOnStaleNews() {
		Map<String, Object> state = new LinkedHashMap<>();
		SkillContext c = ctx(Map.of("market", "US"), state, false);
		skill.run(c, LATE_NOV);
		verify(wiki, never()).monthlyDigest(any(YearMonth.class));
		verify(llm, never()).complete(anyString(), anyString());
		assertThat(state).containsEntry("rebalancedMonth", "2026-11");
		assertThat(c.events()).anyMatch(e -> e.contains("건너뜁니다"));
	}

	@Test
	void doesNothingOutsideMarketHoursOrOnNonTradingDays() {
		aiSays(ranking("XLE", "XLK", "XLF"));
		SkillContext closed = ctx(Map.of("market", "US"), new LinkedHashMap<>(), true);
		skill.run(closed, ZonedDateTime.of(2026, 11, 3, 20, 0, 0, 0, NY));
		assertThat(closed.events()).anyMatch(e -> e.contains("장이 열려 있지 않아"));
		when(calendar.isTradingDay(any(), any())).thenReturn(false);
		skill.run(ctx(Map.of("market", "US"), new LinkedHashMap<>(), false), EARLY_NOV);
		verify(llm, never()).complete(anyString(), anyString());
	}

	@Test
	void whenTooFewEtfsResolveNothingIsRanked() {
		doAnswer(inv -> {
			String s = inv.getArgument(0);
			if (!s.equals("XLK")) {
				throw ApiException.of(ErrorCode.STOCK_NOT_FOUND, "없음");
			}
			return new StockSummary(s, "Technology", null, Market.AMEX, Currency.USD);
		}).when(marketData).resolve(anyString(), any());
		SkillContext c = ctx(Map.of("market", "US"), new LinkedHashMap<>(), false);
		skill.run(c, EARLY_NOV);
		verify(llm, never()).complete(anyString(), anyString());
		assertThat(c.events()).anyMatch(e -> e.contains("산업 ETF가 1개뿐"));
	}
}
