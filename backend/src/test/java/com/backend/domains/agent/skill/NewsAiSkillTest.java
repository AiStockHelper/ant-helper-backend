package com.backend.domains.agent.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
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
import com.backend.common.exception.ErrorCode;
import com.backend.common.properties.AppProperties;
import com.backend.domains.agent.AgentPosition;
import com.backend.domains.agent.AgentTask;
import com.backend.domains.agent.AgentTradingService;
import com.backend.domains.agent.AgentTradingService.LedgerView;
import com.backend.domains.agent.Limits;
import com.backend.domains.chat.ai.LlmGateway;
import com.backend.domains.member.domain.MemberAccount;
import com.backend.domains.news.NewsFeedService;
import com.backend.domains.news.NewsItem;
import com.backend.domains.trade.TradePlanner;
import com.backend.domains.trade.TradePlanner.Plan;
import com.backend.market.Currency;
import com.backend.market.Market;
import com.backend.market.MarketDataService;
import com.backend.market.TradingCalendar;
import com.backend.market.dto.MarketDtos.OrderOutcome;
import com.backend.market.dto.MarketDtos.Quote;
import com.backend.market.dto.MarketDtos.StockSummary;

class NewsAiSkillTest {

	private static final ZoneId NY = ZoneId.of("America/New_York");
	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
	/** 2026-11-10(화) 10:00 뉴욕 = 미국 장 중 */
	private static final ZonedDateTime US_OPEN = ZonedDateTime.of(2026, 11, 10, 10, 0, 0, 0, NY);
	/** 2026-11-10(화) 10:00 서울 = 한국 장 중 */
	private static final ZonedDateTime KR_OPEN = ZonedDateTime.of(2026, 11, 10, 10, 0, 0, 0, SEOUL);

	private NewsFeedService news;
	private LlmGateway llm;
	private AppProperties props;
	private NewsAiSkill skill;
	private MarketDataService marketData;
	private AgentTradingService trading;
	private TradePlanner planner;
	private TradingCalendar calendar;
	private MemberAccount account;
	private AgentTask task;

	@BeforeEach
	void setUp() {
		news = mock(NewsFeedService.class);
		llm = mock(LlmGateway.class);
		props = new AppProperties();
		skill = new NewsAiSkill(news, llm, props);
		skill.llmTimeoutSeconds = 5;
		marketData = mock(MarketDataService.class);
		trading = mock(AgentTradingService.class);
		planner = mock(TradePlanner.class);
		calendar = mock(TradingCalendar.class);
		account = mock(MemberAccount.class);
		task = new AgentTask(1L, 2L, 3L, NewsAiSkill.ID, "t", "i", "{}", "{}", null, true, null, null, null, 0, AgentTask.CreatedBy.USER);
		when(calendar.isTradingDay(any(), any())).thenReturn(true);
		when(trading.reconcile(any(), any())).thenReturn(List.of());
		when(news.recent(anyString(), any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(headlines(12));
		// 미국 섹터 ETF 중 XLK, XLE, XLF 만 종목 마스터에 있다고 가정
		when(marketData.resolve(anyString(), any())).thenAnswer(inv -> {
			String s = inv.getArgument(0);
			return switch (s) {
				case "XLK" -> new StockSummary("XLK", "Technology Select Sector SPDR", null, Market.AMEX, Currency.USD);
				case "XLE" -> new StockSummary("XLE", "Energy Select Sector SPDR", null, Market.AMEX, Currency.USD);
				case "XLF" -> new StockSummary("XLF", "Financial Select Sector SPDR", null, Market.AMEX, Currency.USD);
				case "266370" -> new StockSummary("266370", "KODEX IT", null, Market.KRX, Currency.KRW);
				case "091160" -> new StockSummary("091160", "091160", null, Market.KRX, Currency.KRW);      // 종목 마스터에 없는 6자리 코드의 대체 응답
				default -> throw ApiException.of(ErrorCode.STOCK_NOT_FOUND, "없음: " + s);
			};
		});
		when(marketData.quote(any(), any(), anyString())).thenAnswer(inv -> new Quote(inv.getArgument(2), "n", inv.getArgument(1), Currency.USD,
			BigDecimal.valueOf(101), BigDecimal.ZERO, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0, OffsetDateTime.now()));
		when(planner.plan(any(), anyString(), any(), any(), any(), any(), any())).thenAnswer(inv -> new Plan(Market.AMEX, inv.getArgument(1),
			"Name " + inv.getArgument(1), inv.getArgument(3), 5L, null, BigDecimal.valueOf(100), "지정가"));
		OrderOutcome ok = new OrderOutcome(true, "접수", "1", "BUY", "XLK", "n", Market.AMEX, Currency.USD, 1, BigDecimal.ONE);
		when(trading.buy(any(), any(), any(), any())).thenReturn(ok);
		when(trading.sell(any(), any(), any(), any())).thenReturn(ok);
		ledger();
	}

	private static List<NewsItem> headlines(int n) {
		List<NewsItem> list = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			list.add(new NewsItem("CNBC", "Headline " + i, "Summary " + i, Instant.parse("2026-11-10T13:00:00Z").minusSeconds(i * 600L)));
		}
		return list;
	}

	private void ledger(AgentPosition... open) {
		Limits limits = skill.limits(skill.normalize(Map.of("market", "US")));
		BigDecimal invested = BigDecimal.ZERO;
		for (AgentPosition p : open) {
			invested = invested.add(p.cost());
		}
		when(trading.view(any())).thenReturn(new LedgerView(limits, BigDecimal.ZERO, BigDecimal.ZERO, invested,
			limits.budget().subtract(invested), BigDecimal.ZERO, List.of(open)));
	}

	private SkillContext ctx(Map<String, Object> config, Map<String, Object> state, boolean manual) {
		return new SkillContext(task, account, skill.normalize(config), state, manual,
			new SkillContext.Services(trading, marketData, null, planner, calendar));
	}

	private void aiSays(String json) {
		when(llm.complete(anyString(), anyString())).thenReturn(json);
	}

	// ─────────────────────────── 설정 ───────────────────────────

	@Test
	void defaultsAreConservativeEtfBasedAndPerMarket() {
		Map<String, Object> us = skill.normalize(Map.of("market", "US"));
		assertThat(us).containsEntry("mode", "TRADE").containsEntry("universe", "ETF").containsEntry("maxPositions", 3)
			.containsEntry("minConviction", 4).containsEntry("takeProfitPct", 8.0).containsEntry("stopLossPct", 5.0)
			.containsEntry("maxHoldDays", 10).containsEntry("maxLossPct", 10.0).containsEntry("checkIntervalMinutes", 30);
		assertThat(us.get("budget")).isEqualTo(3000.0);
		Limits l = skill.limits(us);
		assertThat(l.currency()).isEqualTo("USD");
		assertThat(l.positionCap()).isCloseTo(BigDecimal.valueOf(1000), within(new BigDecimal("0.01")));
		assertThat(l.lossLimitAmount()).isEqualByComparingTo("300");
		assertThat(skill.schedule(us).describe()).contains("미국장").contains("30분마다");
		assertThat(skill.title(us)).contains("미국").contains("3,000.00달러");

		Map<String, Object> kr = skill.normalize(Map.of());
		assertThat(kr).containsEntry("market", "KRX");
		assertThat(kr.get("budget")).isEqualTo(5_000_000.0);
		assertThat(skill.limits(kr).currency()).isEqualTo("KRW");
		assertThat(skill.schedule(kr).describe()).contains("한국장");
		assertThat(skill.title(skill.normalize(Map.of("mode", "RECORD")))).contains("기록만");
	}

	@Test
	void everyParamDefaultPassesNormalizeAndBadValuesAreRejected() {
		Map<String, Object> defaults = new LinkedHashMap<>();
		skill.params().forEach(p -> {
			if (p.defaultValue() != null) {
				defaults.put(p.key(), p.defaultValue());
			}
		});
		assertThat(skill.normalize(defaults)).containsEntry("market", "KRX");
		assertThatThrownBy(() -> skill.normalize(Map.of("market", "JP"))).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> skill.normalize(Map.of("mode", "YOLO"))).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> skill.normalize(Map.of("universe", "CRYPTO"))).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> skill.normalize(Map.of("minConviction", 2))).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> skill.normalize(Map.of("maxPositions", 50))).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> skill.normalize(Map.of("budget", "abc"))).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> skill.normalize(Map.of("market", "US", "budget", 10))).isInstanceOf(ApiException.class);
		String tooMany = String.join(",", java.util.stream.IntStream.range(0, 41).mapToObj(i -> "T" + i).toList());
		assertThatThrownBy(() -> skill.normalize(Map.of("symbols", tooMany))).isInstanceOf(ApiException.class);
		assertThat(skill.normalize(Map.of("symbols", "xlk, XLE xlk"))).containsEntry("symbols", "XLK,XLE");
	}

	// ─────────────────────────── 실행 ───────────────────────────

	@Test
	void buysOnlyStrongPicksThatAreInTheCandidateList() {
		aiSays("""
			{"marketView":"기술주 강세","buys":[{"symbol":"XLK","conviction":5,"reason":"실적 호조"},{"symbol":"XLE","conviction":3,"reason":"유가"},
			{"symbol":"NVDA","conviction":5,"reason":"목록 밖"}],"sells":[]}
			""");
		Map<String, Object> state = new LinkedHashMap<>();
		SkillContext c = ctx(Map.of("market", "US"), state, false);
		skill.run(c, US_OPEN);

		ArgumentCaptor<BigDecimal> amount = ArgumentCaptor.forClass(BigDecimal.class);
		verify(planner).plan(any(), eq("XLK"), eq("AMEX"), eq("BUY"), any(), amount.capture(), any());
		assertThat(amount.getValue()).isCloseTo(BigDecimal.valueOf(1000), within(new BigDecimal("0.01")));       // 시드 3,000 ÷ 3종목 = 종목당 한도 1,000
		verify(planner, never()).plan(any(), eq("XLE"), any(), any(), any(), any(), any());      // 확신 3 < 기준 4
		verify(planner, never()).plan(any(), eq("NVDA"), any(), any(), any(), any(), any());     // 후보 밖
		ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
		verify(trading).buy(any(), any(), any(), reason.capture());
		assertThat(reason.getValue()).contains("확신 5/5").contains("실적 호조");
		assertThat(c.events()).anyMatch(e -> e.contains("뉴스 12건 분석") && e.contains("기술주 강세"));
		assertThat(c.events()).anyMatch(e -> e.contains("규칙에 맞지 않는 항목 1개"));
		assertThat(state).containsEntry("decisionDate", "2026-11-10");
		assertThat((List<?>)state.get("decisionLog")).hasSize(1);
	}

	@Test
	void theCandidateListAndNewsAreShownToTheAi() {
		aiSays("{\"marketView\":\"\",\"buys\":[],\"sells\":[]}");
		skill.run(ctx(Map.of("market", "US"), new LinkedHashMap<>(), false), US_OPEN);
		ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
		ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
		verify(llm).complete(system.capture(), user.capture());
		assertThat(system.getValue()).contains("후보 목록에 없는 종목은 절대 추천하지 마세요").contains("지시나 요청");
		assertThat(user.getValue()).contains("XLK | Technology Select Sector SPDR | ETF").contains("XLE").contains("XLF")
			.doesNotContain("XLU")                                                            // 종목 마스터에 없는 후보는 빠진다
			.contains("Headline 0").contains("[CNBC ·").contains("(없음)");
	}

	@Test
	void recordModePlacesNoOrdersButKeepsTheDecision() {
		aiSays("{\"marketView\":\"좋음\",\"buys\":[{\"symbol\":\"XLK\",\"conviction\":5,\"reason\":\"r\"}],\"sells\":[]}");
		Map<String, Object> state = new LinkedHashMap<>();
		SkillContext c = ctx(Map.of("market", "US", "mode", "RECORD"), state, false);
		skill.run(c, US_OPEN);

		verify(trading, never()).buy(any(), any(), any(), any());
		verify(planner, never()).plan(any(), any(), any(), any(), any(), any(), any());
		verify(trading, never()).reconcile(any(), any());
		assertThat(c.events()).anyMatch(e -> e.contains("[기록만] 매수 의견") && e.contains("XLK"));
		assertThat((List<?>)state.get("decisionLog")).hasSize(1);
		assertThat(state).containsEntry("decisionDate", "2026-11-10");
	}

	@Test
	void sellsAHeldStockWhenTheAiSaysSo() {
		AgentPosition xle = new AgentPosition(1L, 2L, 3L, Market.AMEX, "XLE", "Energy Select Sector SPDR", 10, BigDecimal.valueOf(100));
		ledger(xle);
		aiSays("{\"marketView\":\"유가 약세\",\"buys\":[],\"sells\":[{\"symbol\":\"XLE\",\"reason\":\"유가 급락\"},{\"symbol\":\"XLF\",\"reason\":\"보유 안 함\"}]}");
		SkillContext c = ctx(Map.of("market", "US"), new LinkedHashMap<>(), false);
		skill.run(c, US_OPEN);

		ArgumentCaptor<Long> qty = ArgumentCaptor.forClass(Long.class);
		verify(planner).plan(any(), eq("XLE"), eq("AMEX"), eq("SELL"), qty.capture(), any(), any());
		assertThat(qty.getValue()).isEqualTo(10L);
		ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
		verify(trading).sell(any(), any(), any(), reason.capture());
		assertThat(reason.getValue()).contains("AI 판단 매도").contains("유가 급락");
		verify(planner, never()).plan(any(), eq("XLF"), any(), any(), any(), any(), any());
		verify(trading, never()).buy(any(), any(), any(), any());
	}

	@Test
	void theAiSeesHeldPositionsWithProfitAndDaysHeld() {
		ledger(new AgentPosition(1L, 2L, 3L, Market.AMEX, "XLE", "Energy Select Sector SPDR", 10, BigDecimal.valueOf(100)));
		aiSays("{\"buys\":[],\"sells\":[]}");
		skill.run(ctx(Map.of("market", "US"), new LinkedHashMap<>(), false), US_OPEN);
		ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
		verify(llm).complete(anyString(), user.capture());
		assertThat(user.getValue()).contains("XLE | Energy Select Sector SPDR | 0일 | +1.0%").contains("최대 종목 수: 2");
	}

	@Test
	void doesNothingWithoutEnoughNewsAndGivesUpAfterThreeTries() {
		when(news.recent(anyString(), any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(headlines(3));
		Map<String, Object> state = new LinkedHashMap<>();
		for (int i = 1; i <= 3; i++) {
			SkillContext c = ctx(Map.of("market", "US"), state, false);
			skill.run(c, US_OPEN);
			assertThat(c.events()).anyMatch(e -> e.contains("뉴스를 충분히 모으지 못해"));
			assertThat(state).containsEntry("attempts", i).doesNotContainKey("decisionDate");
		}
		SkillContext last = ctx(Map.of("market", "US"), state, false);
		skill.run(last, US_OPEN);
		assertThat(last.events()).anyMatch(e -> e.contains("건너뜁니다"));
		assertThat(state).containsEntry("decisionDate", "2026-11-10");
		verify(llm, never()).complete(anyString(), anyString());
		verify(trading, never()).buy(any(), any(), any(), any());
	}

	@Test
	void aMissingOrUnreadableAiAnswerMeansNoTradesAndARetryLater() {
		Map<String, Object> state = new LinkedHashMap<>();
		when(llm.complete(anyString(), anyString())).thenReturn(null);
		SkillContext c1 = ctx(Map.of("market", "US"), state, false);
		skill.run(c1, US_OPEN);
		assertThat(c1.events()).anyMatch(e -> e.contains("AI 응답을 받지 못했습니다"));
		aiSays("죄송하지만 오늘은 판단하기 어렵습니다.");
		SkillContext c2 = ctx(Map.of("market", "US"), state, false);
		skill.run(c2, US_OPEN);
		assertThat(c2.events()).anyMatch(e -> e.contains("해석하지 못했습니다"));
		assertThat(state).containsEntry("attempts", 2).doesNotContainKey("decisionDate");
		verify(trading, never()).buy(any(), any(), any(), any());
	}

	@Test
	void anAiCallThatNeverReturnsTimesOutInsteadOfHangingTheTask() {
		skill.llmTimeoutSeconds = 1;
		when(llm.complete(anyString(), anyString())).thenAnswer(inv -> {
			Thread.sleep(5_000);
			return "{}";
		});
		SkillContext c = ctx(Map.of("market", "US"), new LinkedHashMap<>(), false);
		long t0 = System.nanoTime();
		skill.run(c, US_OPEN);
		assertThat((System.nanoTime() - t0) / 1_000_000).isLessThan(4_000);
		assertThat(c.events()).anyMatch(e -> e.contains("AI 응답을 받지 못했습니다"));
		verify(trading, never()).buy(any(), any(), any(), any());
	}

	@Test
	void analyzesOnlyOncePerDayAndSkipsWhenTheMarketIsClosed() {
		aiSays("{\"buys\":[],\"sells\":[]}");
		Map<String, Object> state = new LinkedHashMap<>();
		skill.run(ctx(Map.of("market", "US"), state, false), US_OPEN);
		skill.run(ctx(Map.of("market", "US"), state, false), US_OPEN.plusMinutes(30));
		verify(llm).complete(anyString(), anyString());                                   // 두 번째 실행은 AI 를 부르지 않는다
		SkillContext manual = ctx(Map.of("market", "US"), state, true);
		skill.run(manual, US_OPEN.plusHours(1));
		assertThat(manual.events()).anyMatch(e -> e.contains("이미 뉴스를 분석했습니다"));

		SkillContext closed = ctx(Map.of("market", "US"), new LinkedHashMap<>(), true);
		skill.run(closed, ZonedDateTime.of(2026, 11, 10, 20, 0, 0, 0, NY));
		assertThat(closed.events()).anyMatch(e -> e.contains("장이 열려 있지 않아"));
		verify(llm).complete(anyString(), anyString());                                   // 그대로 1회
	}

	@Test
	void holidaysAreSkipped() {
		when(calendar.isTradingDay(any(), any())).thenReturn(false);
		skill.run(ctx(Map.of("market", "US"), new LinkedHashMap<>(), false), US_OPEN);
		verify(llm, never()).complete(anyString(), anyString());
	}

	@Test
	void koreanTaskUsesKoreanFeedsAndKoreanEtfCandidates() {
		ledger();
		when(news.recent(eq("KRX"), any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(headlines(10));
		aiSays("{\"marketView\":\"반도체 약세\",\"buys\":[{\"symbol\":\"266370\",\"conviction\":4,\"reason\":\"IT 실적\"}],\"sells\":[]}");
		when(planner.plan(any(), eq("266370"), any(), any(), any(), any(), any())).thenAnswer(inv -> new Plan(Market.KRX, "266370", "KODEX IT",
			"BUY", 10L, null, BigDecimal.valueOf(30000), "시장가"));
		LedgerView krView = new LedgerView(skill.limits(skill.normalize(Map.of())), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
			BigDecimal.valueOf(5_000_000), BigDecimal.ZERO, List.of());
		when(trading.view(any())).thenReturn(krView);
		SkillContext c = ctx(Map.of(), new LinkedHashMap<>(), false);
		skill.run(c, KR_OPEN);

		verify(news).recent(eq("KRX"), any(), org.mockito.ArgumentMatchers.anyInt());
		ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
		verify(llm).complete(anyString(), user.capture());
		assertThat(user.getValue()).contains("266370 | KODEX IT | ETF · IT").doesNotContain("091160");     // 마스터에 없는 코드는 후보에서 제외
		ArgumentCaptor<BigDecimal> amount = ArgumentCaptor.forClass(BigDecimal.class);
		verify(planner).plan(any(), eq("266370"), eq("KRX"), eq("BUY"), any(), amount.capture(), any());
		assertThat(amount.getValue()).isCloseTo(new BigDecimal("1666666.67"), within(new BigDecimal("0.02")));    // 500만원 ÷ 3종목 한도
		verify(trading).buy(any(), any(), any(), any());
	}

	@Test
	void customSymbolsReplaceTheDefaultCandidates() {
		aiSays("{\"buys\":[],\"sells\":[]}");
		skill.run(ctx(Map.of("market", "US", "symbols", "XLK,ZZZ"), new LinkedHashMap<>(), false), US_OPEN);
		ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
		verify(llm).complete(anyString(), user.capture());
		assertThat(user.getValue()).contains("XLK | Technology Select Sector SPDR | 종목").doesNotContain("XLE |").doesNotContain("ZZZ");
	}

	@Test
	void stopsAtTheStopLossBeforeAskingTheAi() {
		AgentPosition xle = new AgentPosition(1L, 2L, 3L, Market.AMEX, "XLE", "Energy Select Sector SPDR", 10, BigDecimal.valueOf(120));
		ledger(xle);                                                                       // 평균 120 → 현재 101: -15.8%
		aiSays("{\"buys\":[],\"sells\":[]}");
		SkillContext c = ctx(Map.of("market", "US", "stopLossPct", 5), new LinkedHashMap<>(), false);
		skill.run(c, US_OPEN);
		verify(planner).plan(any(), eq("XLE"), eq("AMEX"), eq("SELL"), any(), any(), any());
		ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
		verify(trading).sell(any(), any(), any(), reason.capture());
		assertThat(reason.getValue()).contains("손절선");
	}
}
