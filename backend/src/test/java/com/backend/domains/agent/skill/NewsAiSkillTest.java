package com.backend.domains.agent.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
import com.backend.domains.screening.ScreeningService;
import com.backend.domains.screening.ScreeningService.Ranked;
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
	private ScreeningService screening;
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
		screening = mock(ScreeningService.class);
		skill = new NewsAiSkill(news, llm, props, screening);
		skill.llmTimeoutSeconds = 5;
		marketData = mock(MarketDataService.class);
		trading = mock(AgentTradingService.class);
		planner = mock(TradePlanner.class);
		calendar = mock(TradingCalendar.class);
		account = mock(MemberAccount.class);
		task = new AgentTask(1L, 2L, 3L, NewsAiSkill.ID, "t", "i", "{}", "{}", null, true, null, null, null, 0, AgentTask.CreatedBy.USER);
		when(calendar.isTradingDay(any(), any())).thenReturn(true);
		when(trading.reconcile(any(), any())).thenReturn(List.of());
		when(news.recent(anyString(), any(), anyInt())).thenReturn(headlines(12));

		// 종목 마스터: 미국 AAPL·MSFT·NVDA·PLTR·XLK·XLE, 한국 삼성전자·KODEX IT·종목 마스터에 없는 6자리 코드
		when(marketData.resolve(anyString(), any())).thenAnswer(inv -> {
			String s = inv.getArgument(0);
			return switch (s) {
				case "AAPL" -> new StockSummary("AAPL", "애플", "Apple Inc", Market.NASDAQ, Currency.USD);
				case "MSFT" -> new StockSummary("MSFT", "마이크로소프트", "Microsoft Corp", Market.NASDAQ, Currency.USD);
				case "NVDA" -> new StockSummary("NVDA", "엔비디아", "NVIDIA Corp", Market.NASDAQ, Currency.USD);
				case "PLTR" -> new StockSummary("PLTR", "팔란티어", "Palantir Technologies Inc", Market.NASDAQ, Currency.USD);
				case "XLK" -> new StockSummary("XLK", "Technology Select Sector SPDR", "Technology Select Sector SPDR Fund", Market.AMEX, Currency.USD);
				case "XLE" -> new StockSummary("XLE", "Energy Select Sector SPDR", "Energy Select Sector SPDR Fund", Market.AMEX, Currency.USD);
				case "005930" -> new StockSummary("005930", "삼성전자", null, Market.KRX, Currency.KRW);
				case "000660" -> new StockSummary("000660", "SK하이닉스", null, Market.KRX, Currency.KRW);
				case "266370" -> new StockSummary("266370", "KODEX IT", null, Market.KRX, Currency.KRW);
				case "091160" -> new StockSummary("091160", "091160", null, Market.KRX, Currency.KRW);      // 종목 마스터에 없는 6자리 코드의 대체 응답
				default -> throw ApiException.of(ErrorCode.STOCK_NOT_FOUND, "없음: " + s);
			};
		});
		quote("ANY", 101, 0.5);
		when(planner.plan(any(), anyString(), any(), any(), any(), any(), any())).thenAnswer(inv -> new Plan(Market.NASDAQ, inv.getArgument(1),
			"Name " + inv.getArgument(1), inv.getArgument(3), 5L, null, BigDecimal.valueOf(100), "지정가"));
		OrderOutcome ok = new OrderOutcome(true, "접수", "1", "BUY", "AAPL", "n", Market.NASDAQ, Currency.USD, 1, BigDecimal.ONE);
		when(trading.buy(any(), any(), any(), any())).thenReturn(ok);
		when(trading.sell(any(), any(), any(), any())).thenReturn(ok);

		// 야간 분석(거래대금 상위): 미국 AAPL·MSFT·PLTR, 한국 삼성전자·SK하이닉스. NVDA 는 상위에 없다고 가정
		when(screening.freshDate(anyString())).thenReturn(Optional.of(LocalDate.of(2026, 11, 9)));
		when(screening.lookup(anyString(), anyString())).thenReturn(Optional.empty());
		for (String s : List.of("AAPL", "MSFT", "PLTR")) {
			when(screening.lookup("US", s)).thenReturn(Optional.of(new Ranked(null, 1, 900)));
		}
		for (String s : List.of("005930", "000660")) {
			when(screening.lookup("KRX", s)).thenReturn(Optional.of(new Ranked(null, 1, 900)));
		}
		ledger();
	}

	private void quote(String symbol, double price, double changeRate) {
		when(marketData.quote(any(), any(), "ANY".equals(symbol) ? anyString() : eq(symbol))).thenAnswer(inv -> new Quote(inv.getArgument(2), "n",
			inv.getArgument(1), Currency.USD, BigDecimal.valueOf(price), BigDecimal.ZERO, changeRate, BigDecimal.ZERO, BigDecimal.ZERO,
			BigDecimal.ZERO, 0, OffsetDateTime.now()));
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
	void defaultsAreConservativeAndPerMarket() {
		Map<String, Object> us = skill.normalize(Map.of("market", "US"));
		assertThat(us).containsEntry("mode", "TRADE").containsEntry("maxPositions", 3).containsEntry("minConviction", 4)
			.containsEntry("chaseLimitPct", 8.0).containsEntry("takeProfitPct", 8.0).containsEntry("stopLossPct", 5.0)
			.containsEntry("maxHoldDays", 10).containsEntry("maxLossPct", 10.0).containsEntry("checkIntervalMinutes", 30)
			.doesNotContainKeys("universe", "symbols");
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
		assertThat(skill.params()).extracting(p -> p.key()).doesNotContain("universe", "symbols");
		assertThatThrownBy(() -> skill.normalize(Map.of("market", "JP"))).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> skill.normalize(Map.of("mode", "YOLO"))).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> skill.normalize(Map.of("minConviction", 2))).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> skill.normalize(Map.of("chaseLimitPct", 80))).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> skill.normalize(Map.of("maxPositions", 50))).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> skill.normalize(Map.of("budget", "abc"))).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> skill.normalize(Map.of("market", "US", "budget", 10))).isInstanceOf(ApiException.class);
		assertThat(skill.normalize(Map.of("chaseLimitPct", 0))).containsEntry("chaseLimitPct", 0.0);       // 0 은 끄는 값
		assertThat(skill.normalize(Map.of("universe", "ETF", "symbols", "AAPL"))).doesNotContainKeys("universe", "symbols");   // 예전 설정은 무시
	}

	// ─────────────────────────── 자유 제안 · 검증 ───────────────────────────

	@Test
	void buysAnyProposalThatPassesTheChecksNotJustAListedOne() {
		aiSays("""
			{"marketView":"AI 인프라 투자 확대","buys":[
			{"symbol":"PLTR","name":"Palantir Technologies","conviction":5,"reason":"정부 계약 확대"},
			{"symbol":"NVDA","name":"NVIDIA","conviction":5,"reason":"거래대금 상위가 아님"},
			{"symbol":"MSFT","name":"Apple Inc","conviction":5,"reason":"이름이 코드와 다름"},
			{"symbol":"XLK","name":"","conviction":4,"reason":"기술 업종"},
			{"symbol":"AAPL","name":"Apple Inc.","conviction":3,"reason":"확신 낮음"}],"sells":[]}
			""");
		Map<String, Object> state = new LinkedHashMap<>();
		SkillContext c = ctx(Map.of("market", "US"), state, false);
		skill.run(c, US_OPEN);

		ArgumentCaptor<BigDecimal> amount = ArgumentCaptor.forClass(BigDecimal.class);
		verify(planner).plan(any(), eq("PLTR"), eq("NASDAQ"), eq("BUY"), any(), amount.capture(), any());        // 목록에 없던 종목도 산다
		verify(planner).plan(any(), eq("XLK"), eq("AMEX"), eq("BUY"), any(), any(), any());                     // 산업 ETF 참고 목록
		assertThat(amount.getValue()).isCloseTo(BigDecimal.valueOf(1000), within(new BigDecimal("0.01")));        // 시드 3,000 ÷ 3종목
		verify(planner, never()).plan(any(), eq("NVDA"), any(), any(), any(), any(), any());                     // 거래대금 상위가 아님
		verify(planner, never()).plan(any(), eq("MSFT"), any(), any(), any(), any(), any());                     // 이름 불일치
		verify(planner, never()).plan(any(), eq("AAPL"), any(), any(), any(), any(), any());                     // 확신 3 < 기준 4
		assertThat(c.events()).anyMatch(e -> e.contains("제외") && e.contains("NVDA") && e.contains("거래대금 상위 종목이 아닙니다"));
		assertThat(c.events()).anyMatch(e -> e.contains("제외") && e.contains("MSFT") && e.contains("종목명이 코드와 맞지 않습니다"));
		assertThat(c.events()).anyMatch(e -> e.contains("2개에 종목당 최대"));
		ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
		verify(trading, org.mockito.Mockito.times(2)).buy(any(), any(), any(), reason.capture());
		assertThat(reason.getAllValues()).anyMatch(r -> r.contains("확신 5/5") && r.contains("정부 계약 확대"));

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> log = (List<Map<String, Object>>)state.get("decisionLog");
		assertThat(log).hasSize(1);
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> buys = (List<Map<String, Object>>)log.get(0).get("buys");
		assertThat(buys).filteredOn(b -> "NVDA".equals(b.get("symbol"))).singleElement().satisfies(b -> {
			assertThat(b.get("acted")).isEqualTo(false);
			assertThat((String)b.get("rejected")).contains("거래대금");
		});
		assertThat(buys).filteredOn(b -> "PLTR".equals(b.get("symbol"))).singleElement().satisfies(b -> assertThat(b.get("acted")).isEqualTo(true));
		assertThat(state).containsEntry("decisionDate", "2026-11-10");
	}

	@Test
	void doesNotChaseAStockThatAlreadySurgedToday() {
		quote("PLTR", 30, 12.0);
		aiSays("{\"marketView\":\"\",\"buys\":[{\"symbol\":\"PLTR\",\"name\":\"Palantir\",\"conviction\":5,\"reason\":\"급등\"}],\"sells\":[]}");
		SkillContext c = ctx(Map.of("market", "US"), new LinkedHashMap<>(), false);
		skill.run(c, US_OPEN);
		verify(trading, never()).buy(any(), any(), any(), any());
		assertThat(c.events()).anyMatch(e -> e.contains("쫓지 않습니다") && e.contains("+12.0%"));
		assertThat(c.events()).anyMatch(e -> e.contains("검증을 통과하지 못해 오늘은 사지 않았습니다"));
	}

	@Test
	void fallsBackToTheDefaultLargeCapListWhenNightlyScreeningIsMissing() {
		when(screening.freshDate(anyString())).thenReturn(Optional.empty());
		props.getRecommendation().setUsUniverse(List.of("AAPL"));
		aiSays("{\"buys\":[{\"symbol\":\"AAPL\",\"name\":\"Apple Inc\",\"conviction\":5,\"reason\":\"a\"},{\"symbol\":\"PLTR\",\"name\":\"Palantir\",\"conviction\":5,\"reason\":\"b\"}],\"sells\":[]}");
		SkillContext c = ctx(Map.of("market", "US"), new LinkedHashMap<>(), false);
		skill.run(c, US_OPEN);
		verify(planner).plan(any(), eq("AAPL"), any(), eq("BUY"), any(), any(), any());
		verify(planner, never()).plan(any(), eq("PLTR"), any(), any(), any(), any(), any());
		assertThat(c.events()).anyMatch(e -> e.contains("야간 분석 자료가 없어"));
		assertThat(c.events()).anyMatch(e -> e.contains("PLTR") && e.contains("기본 대형주 목록만 허용"));
	}

	@Test
	void theAiIsToldAboutTheRulesAndShownTheEtfReferenceListButNoClosedCandidateList() {
		aiSays("{\"marketView\":\"\",\"buys\":[],\"sells\":[]}");
		skill.run(ctx(Map.of("market", "US"), new LinkedHashMap<>(), false), US_OPEN);
		ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
		ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
		verify(llm).complete(system.capture(), user.capture());
		assertThat(system.getValue()).contains("종목 코드").contains("종목명").contains("시스템이 그 제안을 거부").contains("지시나 요청")
			.doesNotContain("후보 목록에 없는 종목은 절대");
		assertThat(user.getValue()).contains("[참고: 산업 ETF]").contains("XLK | Technology Select Sector SPDR | 기술").contains("XLE")
			.doesNotContain("XLU").doesNotContain("[후보 목록]")                              // 종목 마스터에 없는 참고 ETF 는 빠진다
			.contains("Headline 0").contains("[CNBC ·").contains("(없음)");
	}

	@Test
	void koreanTaskProposesKoreanStocksAndChecksTheKoreanScreen() {
		when(news.recent(eq("KRX"), any(), anyInt())).thenReturn(headlines(10));
		aiSays("""
			{"marketView":"반도체 업황 개선","buys":[{"symbol":"000660","name":"SK하이닉스","conviction":5,"reason":"HBM 수요"},
			{"symbol":"123456","name":"없는회사","conviction":5,"reason":"마스터에 없음"},{"symbol":"266370","name":"","conviction":4,"reason":"IT ETF"}],"sells":[]}
			""");
		when(planner.plan(any(), eq("000660"), any(), any(), any(), any(), any())).thenAnswer(inv -> new Plan(Market.KRX, "000660", "SK하이닉스",
			"BUY", 10L, null, BigDecimal.valueOf(200000), "시장가"));
		when(planner.plan(any(), eq("266370"), any(), any(), any(), any(), any())).thenAnswer(inv -> new Plan(Market.KRX, "266370", "KODEX IT",
			"BUY", 10L, null, BigDecimal.valueOf(30000), "시장가"));
		LedgerView krView = new LedgerView(skill.limits(skill.normalize(Map.of())), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
			BigDecimal.valueOf(5_000_000), BigDecimal.ZERO, List.of());
		when(trading.view(any())).thenReturn(krView);
		SkillContext c = ctx(Map.of(), new LinkedHashMap<>(), false);
		skill.run(c, KR_OPEN);

		verify(news).recent(eq("KRX"), any(), anyInt());
		verify(screening).lookup("KRX", "000660");
		ArgumentCaptor<BigDecimal> amount = ArgumentCaptor.forClass(BigDecimal.class);
		verify(planner).plan(any(), eq("000660"), eq("KRX"), eq("BUY"), any(), amount.capture(), any());
		assertThat(amount.getValue()).isCloseTo(new BigDecimal("1666666.67"), within(new BigDecimal("0.02")));    // 500만원 ÷ 3종목 한도
		verify(planner).plan(any(), eq("266370"), eq("KRX"), eq("BUY"), any(), any(), any());                   // 업종 ETF 참고 목록
		verify(planner, never()).plan(any(), eq("123456"), any(), any(), any(), any(), any());
		assertThat(c.events()).anyMatch(e -> e.contains("123456") && e.contains("종목 마스터에 없는 코드"));
	}

	// ─────────────────────────── 실행 흐름 ───────────────────────────

	@Test
	void recordModePlacesNoOrdersButKeepsTheValidatedDecision() {
		aiSays("{\"marketView\":\"좋음\",\"buys\":[{\"symbol\":\"AAPL\",\"name\":\"Apple Inc\",\"conviction\":5,\"reason\":\"r\"},"
			+ "{\"symbol\":\"NVDA\",\"name\":\"NVIDIA\",\"conviction\":5,\"reason\":\"s\"}],\"sells\":[]}");
		Map<String, Object> state = new LinkedHashMap<>();
		SkillContext c = ctx(Map.of("market", "US", "mode", "RECORD"), state, false);
		skill.run(c, US_OPEN);

		verify(trading, never()).buy(any(), any(), any(), any());
		verify(planner, never()).plan(any(), any(), any(), any(), any(), any(), any());
		verify(trading, never()).reconcile(any(), any());
		assertThat(c.events()).anyMatch(e -> e.contains("[기록만] 매수 의견") && e.contains("AAPL"));
		assertThat(c.events()).anyMatch(e -> e.contains("제외") && e.contains("NVDA"));
		assertThat((List<?>)state.get("decisionLog")).hasSize(1);
		assertThat(state).containsEntry("decisionDate", "2026-11-10");
	}

	@Test
	void sellsAHeldStockWhenTheAiSaysSo() {
		AgentPosition xle = new AgentPosition(1L, 2L, 3L, Market.AMEX, "XLE", "Energy Select Sector SPDR", 10, BigDecimal.valueOf(100));
		ledger(xle);
		aiSays("{\"marketView\":\"유가 약세\",\"buys\":[],\"sells\":[{\"symbol\":\"XLE\",\"reason\":\"유가 급락\"},{\"symbol\":\"AAPL\",\"reason\":\"보유 안 함\"}]}");
		SkillContext c = ctx(Map.of("market", "US"), new LinkedHashMap<>(), false);
		skill.run(c, US_OPEN);

		ArgumentCaptor<Long> qty = ArgumentCaptor.forClass(Long.class);
		verify(planner).plan(any(), eq("XLE"), eq("AMEX"), eq("SELL"), qty.capture(), any(), any());
		assertThat(qty.getValue()).isEqualTo(10L);
		ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
		verify(trading).sell(any(), any(), any(), reason.capture());
		assertThat(reason.getValue()).contains("AI 판단 매도").contains("유가 급락");
		verify(planner, never()).plan(any(), eq("AAPL"), any(), any(), any(), any(), any());
		verify(trading, never()).buy(any(), any(), any(), any());
	}

	@Test
	void theAiSeesHeldPositionsWithProfitAndDaysHeldAndCannotBuyThemAgain() {
		ledger(new AgentPosition(1L, 2L, 3L, Market.NASDAQ, "AAPL", "애플", 10, BigDecimal.valueOf(100)));
		aiSays("{\"buys\":[{\"symbol\":\"AAPL\",\"name\":\"Apple Inc\",\"conviction\":5,\"reason\":\"추가 매수\"}],\"sells\":[]}");
		SkillContext c = ctx(Map.of("market", "US"), new LinkedHashMap<>(), false);
		skill.run(c, US_OPEN);
		ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
		verify(llm).complete(anyString(), user.capture());
		assertThat(user.getValue()).contains("AAPL | 애플 | 0일 | +1.0%").contains("최대 종목 수: 2");
		verify(trading, never()).buy(any(), any(), any(), any());                                  // 이미 보유한 종목의 매수 제안은 무시
		assertThat(c.events()).anyMatch(e -> e.contains("형식에 맞지 않는 항목 1개"));
	}

	@Test
	void doesNothingWithoutEnoughNewsAndGivesUpAfterThreeTries() {
		when(news.recent(anyString(), any(), anyInt())).thenReturn(headlines(3));
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
