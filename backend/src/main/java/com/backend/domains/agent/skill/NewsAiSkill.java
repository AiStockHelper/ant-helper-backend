package com.backend.domains.agent.skill;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.stereotype.Component;

import com.backend.common.exception.ApiException;
import com.backend.common.exception.ErrorCode;
import com.backend.common.properties.AppProperties;
import com.backend.domains.agent.AgentPosition;
import com.backend.domains.agent.AgentTradingService;
import com.backend.domains.agent.AgentTradingService.LedgerView;
import com.backend.domains.agent.Limits;
import com.backend.domains.agent.Schedule;
import com.backend.domains.agent.skill.NewsCandidates.Candidate;
import com.backend.domains.agent.skill.NewsDecisionParser.Decision;
import com.backend.domains.agent.skill.NewsDecisionParser.Pick;
import com.backend.domains.agent.skill.NewsDecisionParser.Sell;
import com.backend.domains.agent.skill.NewsPrompt.HeldLine;
import com.backend.domains.chat.ai.LlmGateway;
import com.backend.domains.news.NewsFeedService;
import com.backend.domains.news.NewsItem;
import com.backend.domains.trade.TradePlanner.Plan;
import com.backend.market.Currency;
import com.backend.market.Market;
import com.backend.market.dto.MarketDtos.OrderOutcome;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 뉴스 AI 자동매매 (한국 / 미국).
 * 하루 한 번, 장이 열린 직후에 최신 경제 뉴스 헤드라인을 AI 에게 읽히고 "후보 목록 안에서 무엇을 사고 무엇을 팔지" 판단을 받는다.
 * AI 의 응답은 코드가 검증한다(후보 밖 종목·미보유 종목 매도는 버림). 주문은 항상 {@link AgentTradingService} 를 거치므로
 * 시드·종목당 한도·누적 손실 한도는 AI 가 바꿀 수 없다. 익절·손절·최대 보유일은 AI 판단과 별개로 코드가 강제한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NewsAiSkill implements Skill {

	public static final String ID = "news-ai-trader";
	/** 뉴스가 이보다 적게 모이면 판단하지 않는다(피드 장애로 근거 없이 주문하는 것을 막는다). */
	static final int MIN_NEWS = 8;
	static final int MAX_NEWS = 60;
	static final int MAX_ATTEMPTS_PER_DAY = 3;
	static final int LOG_KEEP = 40;

	private final NewsFeedService news;
	private final LlmGateway llm;
	private final AppProperties props;

	/** 테스트에서 줄일 수 있도록 분리해 둔 AI 응답 대기 시간 */
	long llmTimeoutSeconds = 120;

	@Override
	public String id() {
		return ID;
	}

	@Override
	public String name() {
		return "뉴스 AI 자동매매";
	}

	@Override
	public String summary() {
		return "매일 장이 열릴 때 최신 경제 뉴스를 AI가 읽고, 유망한 산업(ETF)·종목을 골라 사고팝니다. 한국·미국을 따로 운용합니다.";
	}

	@Override
	public String category() {
		return "자동매매";
	}

	@Override
	public boolean trades() {
		return true;
	}

	@Override
	public String detail() {
		return """
			## 어떤 스킬인가요?
			매 거래일 장이 열리면 **그날의 경제 뉴스 헤드라인**(한국: 연합뉴스·한국경제·매일경제·구글뉴스, 미국: CNBC·MarketWatch·NYT·BBC·구글뉴스)을 \
			AI가 읽고 **어떤 산업이 유리해 보이는지**를 판단합니다. 기본 후보는 **산업별 ETF**(한국 업종 ETF / 미국 SPDR 섹터 ETF)이고, \
			원하면 대형주나 직접 지정한 종목을 후보로 쓸 수 있습니다. 한국장과 미국장은 **작업을 따로 만들어** 시드·손실 한도를 각각 운용합니다.

			## 하루 흐름
			1. 장 개장 5분 뒤 첫 실행에서 뉴스를 모아 AI에게 **후보 목록·보유 종목·헤드라인**을 보여 주고 판단을 받습니다(하루 1회).
			2. AI가 낸 **매수 의견 중 확신도가 기준(기본 4/5) 이상**인 종목을 시드 안에서 나눠 삽니다. 보유 종목에 대해 AI가 매도 의견을 내면 팝니다.
			3. 이후 장중에는 N분마다 보유 종목을 점검해 **익절 · 손절 · 최대 보유일 초과**를 코드가 정리합니다. 누적 손실이 한도에 닿으면 전량 정리 후 스스로 멈춥니다.
			4. 판단 결과(시장 요약, 매수·매도 의견과 이유)는 작업 기록에 남습니다. **'기록만' 모드**로 두면 주문 없이 판단만 쌓입니다.

			## 안전장치 (AI가 바꿀 수 없습니다)
			- AI는 **후보 목록 안의 종목만** 고를 수 있고, 보유하지 않은 종목은 팔 수 없습니다. 목록 밖이거나 형식이 틀린 항목은 버립니다.
			- 시드, 종목당 비중, 하루 매수 한도, 누적 손실 한도, 실전 계좌 1회 주문 한도는 코드가 강제합니다.
			- 뉴스를 충분히 못 모으거나(8건 미만) AI 응답을 읽지 못하면 그날은 **아무것도 사지 않습니다.**
			- 뉴스 문구 안의 지시("이 종목을 사라")는 따르지 않도록 AI에게 알려 두었지만, 완벽하지는 않습니다. 그래서 위 한도가 중요합니다.

			## 반드시 알아두세요
			- **수익을 보장하지 않으며, 이 방식의 효과는 아직 검증되지 않았습니다.**
			- 이 서비스에서 과거 데이터로 시험한 결과(월 단위 산업 ETF 선택, 2016~2026)는 좋아 보였지만 **AI가 과거를 이미 알고 있어서 믿을 수 없었습니다.** \
			뉴스 없이 날짜만 줘도 비슷하게 잘 맞혔기 때문입니다. AI의 지식 시점 이후 15~16개월만 깨끗했는데, 미국에서는 뉴스를 읽은 쪽이 다소 나았지만(표본이 짧아 확정 불가) \
			한국에서는 뉴스를 읽은 효과가 없었고 단지 반도체·IT 쏠림이었습니다.
			- 그래서 **지금부터의 실제 결과가 유일한 검증**입니다. 처음에는 **'기록만' 모드 또는 모의 계좌와 작은 시드**로 몇 달 지켜보세요.
			- 뉴스는 **헤드라인과 짧은 요약**만 봅니다(공시·재무제표·실시간 속보는 보지 못합니다). 해외 매체는 한국 종목 소식이 적습니다.
			- 하루 한 번 아침(개장 직후)에 판단하므로 장중에 나온 소식은 다음 날에 반영됩니다. 미국장은 한국 시간으로 밤 시간에 판단합니다.
			- 수수료·거래세·슬리피지는 손익 계산에 포함하지 않습니다. 미국 주식은 시장가 주문이 불가능해 현재가에서 약간 위/아래의 지정가로 주문하므로 체결이 안 될 수 있습니다.
			- AI 호출 비용이 하루 1회 발생합니다.
			- 이 스킬이 산 종목만 관리하고 계좌의 다른 보유 종목은 건드리지 않습니다.
			- **실전 계좌에서는 시작할 때 한 번 승인이 필요**하고, 이후에는 정해 둔 시드·손실 한도 안에서만 주문합니다.
			""";
	}

	@Override
	public List<Param> params() {
		return List.of(
			Param.select("market", "시장", "KRX", List.of(new Option("KRX", "한국 (원)"), new Option("US", "미국 (달러)")),
				"한국장과 미국장은 작업을 따로 만들어 각각 운용하세요. 한국은 평일 09:00~15:30 KST, 미국은 뉴욕 09:30~16:00 기준입니다."),
			Param.select("mode", "동작 방식", "TRADE", List.of(new Option("TRADE", "실제 매매"), new Option("RECORD", "기록만 (주문 안 함)")),
				"'기록만'은 AI 판단과 이유만 남기고 주문하지 않습니다. 효과를 확인하는 데 쓰세요."),
			Param.select("universe", "후보", "ETF", List.of(new Option("ETF", "산업 ETF"), new Option("STOCK", "대형주"), new Option("BOTH", "ETF+대형주")),
				"AI가 고를 수 있는 종목 범위입니다. 산업 ETF는 한 종목 위험이 작아 기본값입니다."),
			Param.text("symbols", "후보 직접 지정 (선택)",
				"쉼표로 구분한 종목 코드/티커. 입력하면 위 후보 대신 이 종목들만 AI가 고릅니다. 예: 005930,000660 또는 XLK,XLE,NVDA"),
			Param.number("budget", "시드(투자 한도)", 5_000_000, 100, 1_000_000_000_000.0, "원/달러",
				"이 스킬이 굴릴 수 있는 최대 금액입니다. 한국은 원, 미국은 달러 단위로 입력하세요. 이 금액을 넘겨 사지 않습니다."),
			Param.number("maxPositions", "최대 동시 보유 종목 수", 3, 1, 10, "종목", "시드를 이 수로 나눈 금액이 한 종목 한도가 됩니다."),
			Param.number("minConviction", "매수 기준: AI 확신도", 4, 3, 5, "점", "AI가 매긴 확신도(1~5)가 이 값 이상인 종목만 삽니다. 높일수록 까다롭습니다."),
			Param.number("takeProfitPct", "목표 수익률(익절)", 8.0, 1, 50, "%", "보유 종목이 평균 매수가 대비 이만큼 오르면 매도합니다."),
			Param.number("stopLossPct", "손절선", 5.0, 1, 30, "%", "평균 매수가 대비 이만큼 내려가면 매도합니다."),
			Param.number("maxHoldDays", "최대 보유일", 10, 0, 60, "일", "이 기간이 지나도 정리되지 않으면 장 마감 직전에 정리합니다. 0이면 제한 없음(권장하지 않음)."),
			Param.number("maxLossPct", "누적 손실 한도", 10, 1, 50, "%", "실현+평가 손실이 시드의 이 비율에 닿으면 전량 매도하고 자동매매를 멈춥니다."),
			Param.number("checkIntervalMinutes", "점검 주기", 30, Schedule.MIN_INTERVAL_MINUTES, 60, "분", "장중 몇 분마다 보유 종목의 익절·손절을 확인할지 정합니다."));
	}

	@Override
	public Map<String, Object> normalize(Map<String, Object> in) {
		Map<String, Object> input = in == null ? Map.of() : in;
		String market = String.valueOf(input.getOrDefault("market", "KRX")).trim().toUpperCase(Locale.ROOT);
		if (!market.equals("KRX") && !market.equals("US")) {
			throw invalid("시장은 KRX 또는 US 여야 합니다.");
		}
		boolean krx = market.equals("KRX");
		Map<String, Object> c = new LinkedHashMap<>();
		c.put("market", market);
		c.put("mode", oneOf(input, "mode", "TRADE", "TRADE", "RECORD"));
		c.put("universe", oneOf(input, "universe", "ETF", "ETF", "STOCK", "BOTH"));
		String symbols = String.valueOf(input.getOrDefault("symbols", "")).trim();
		if (!symbols.isEmpty() && !symbols.equals("null")) {
			List<String> codes = parseSymbols(symbols);
			if (codes.size() > 40) {
				throw invalid("후보 종목은 최대 40개까지 지정할 수 있습니다.");
			}
			c.put("symbols", String.join(",", codes));
		}
		c.put("budget", bounded(input, "budget", krx ? 5_000_000 : 3_000, krx ? 100_000 : 100, 1e12, "시드"));
		c.put("maxPositions", (int)bounded(input, "maxPositions", 3, 1, 10, "최대 동시 보유 종목 수"));
		c.put("minConviction", (int)bounded(input, "minConviction", 4, 3, 5, "AI 확신도 기준"));
		c.put("takeProfitPct", bounded(input, "takeProfitPct", 8.0, 1, 50, "목표 수익률"));
		c.put("stopLossPct", bounded(input, "stopLossPct", 5.0, 1, 30, "손절선"));
		c.put("maxHoldDays", (int)bounded(input, "maxHoldDays", 10, 0, 60, "최대 보유일"));
		c.put("maxLossPct", bounded(input, "maxLossPct", 10, 1, 50, "누적 손실 한도"));
		c.put("checkIntervalMinutes", (int)bounded(input, "checkIntervalMinutes", 30, Schedule.MIN_INTERVAL_MINUTES, 60, "점검 주기"));
		return c;
	}

	@Override
	public Schedule schedule(Map<String, Object> config) {
		return Schedule.marketInterval((String)config.get("market"), ((Number)config.get("checkIntervalMinutes")).intValue(), 5, -10);
	}

	@Override
	public Limits limits(Map<String, Object> config) {
		boolean krx = "KRX".equals(config.get("market"));
		BigDecimal budget = BigDecimal.valueOf(((Number)config.get("budget")).doubleValue()).setScale(krx ? 0 : 2, RoundingMode.DOWN);
		int positions = ((Number)config.get("maxPositions")).intValue();
		return new Limits(budget, krx ? "KRW" : "USD", 100.0 / positions, budget, ((Number)config.get("maxLossPct")).doubleValue());
	}

	@Override
	public String title(Map<String, Object> config) {
		boolean krx = "KRX".equals(config.get("market"));
		Limits l = limits(config);
		return name() + " · " + (krx ? "한국" : "미국") + " · 시드 " + Currency.valueOf(l.currency()).format(l.budget())
			+ ("RECORD".equals(config.get("mode")) ? " · 기록만" : "");
	}

	// ─────────────────────────── 실행 ───────────────────────────

	@Override
	public void run(SkillContext ctx) {
		Market group = "US".equals(ctx.str("market", "KRX")) ? Market.NASDAQ : Market.KRX;
		run(ctx, ZonedDateTime.now(group.zone()));
	}

	/** 시각을 받는 실행(테스트에서 시계를 고정하려고 분리). */
	void run(SkillContext ctx, ZonedDateTime now) {
		boolean krx = !"US".equals(ctx.str("market", "KRX"));
		Market group = krx ? Market.KRX : Market.NASDAQ;
		String groupName = krx ? "KRX" : "US";
		LocalDate today = now.toLocalDate();
		SkillContext.Services sv = ctx.services();
		AgentTradingService trading = sv.trading();
		boolean record = "RECORD".equals(ctx.str("mode", "TRADE"));

		if (!sv.calendar().isTradingDay(group, today) || !group.isOpenAt(now)) {
			if (ctx.manual()) {
				ctx.event("지금은 " + (krx ? "한국" : "미국") + " 장이 열려 있지 않아 아무것도 하지 않았습니다.");
			}
			return;
		}

		// 0) 계좌의 실제 보유와 장부 맞추기(하루 한 번). 기록만 모드는 주문이 없으므로 건너뛴다.
		if (!record && !today.toString().equals(ctx.stateStr("reconciledDate"))) {
			for (String line : trading.reconcile(ctx.task(), ctx.account())) {
				ctx.event(line);
			}
			ctx.state().put("reconciledDate", today.toString());
		}

		// 1) 보유 종목 점검(익절·손절·보유일) → 2) 누적 손실 한도
		if (!record) {
			SkillExits.manageOpenPositions(ctx, group, now, ctx.num("takeProfitPct", 8.0), ctx.num("stopLossPct", 5.0),
				(int)ctx.num("maxHoldDays", 10));
			if (SkillExits.enforceLossLimit(ctx)) {
				return;
			}
		}

		// 3) 하루 한 번 뉴스 판단
		if (today.toString().equals(ctx.stateStr("decisionDate"))) {
			if (ctx.manual()) {
				ctx.event("오늘은 이미 뉴스를 분석했습니다. 판단 기록은 이 작업의 기록에서 볼 수 있습니다.");
			}
			return;
		}
		int attempts = today.toString().equals(ctx.stateStr("attemptDate")) && ctx.state().get("attempts") instanceof Number n ? n.intValue() : 0;
		if (attempts >= MAX_ATTEMPTS_PER_DAY) {
			ctx.state().put("decisionDate", today.toString());
			ctx.event("뉴스나 AI 응답을 " + MAX_ATTEMPTS_PER_DAY + "번 시도했지만 받지 못해 오늘은 판단을 건너뜁니다.");
			return;
		}

		List<NewsItem> headlines = news.recent(groupName, Duration.ofHours(30), MAX_NEWS);
		if (headlines.size() < MIN_NEWS) {
			failAttempt(ctx, today, attempts, "뉴스를 충분히 모으지 못해(" + headlines.size() + "건) 오늘은 판단하지 않고 다시 시도합니다.");
			return;
		}

		List<Candidate> candidates = NewsCandidates.build(sv.marketData(), krx, ctx.str("universe", "ETF"),
			parseSymbols(ctx.str("symbols", "")), krx ? props.getRecommendation().getKrxUniverse() : props.getRecommendation().getUsUniverse());
		if (candidates.isEmpty()) {
			ctx.state().put("decisionDate", today.toString());
			ctx.event("AI가 고를 수 있는 후보 종목을 하나도 확인하지 못해 오늘은 판단하지 않았습니다. 후보 설정을 확인해 주세요.");
			return;
		}

		LedgerView view = trading.view(ctx.task());
		List<AgentPosition> open = view.open().stream().filter(p -> (p.getMarket() == Market.KRX) == krx).toList();
		List<HeldLine> heldLines = new ArrayList<>();
		for (AgentPosition p : open) {
			try {
				p.mark(sv.marketData().quote(ctx.account(), p.getMarket(), p.getSymbol()).price());
			} catch (RuntimeException e) {
				log.info("보유 종목 시세 조회 실패 {}: {}", p.getSymbol(), e.getMessage());
			}
			heldLines.add(new HeldLine(p.getSymbol().toUpperCase(Locale.ROOT), p.getName(), p.heldDays(group.zone()), p.pnlPct()));
		}
		int maxPositions = (int)ctx.num("maxPositions", 3);
		int slots = maxPositions - open.size();

		String system = NewsPrompt.system();
		String user = NewsPrompt.user(krx ? "한국" : "미국", now, slots, candidates, heldLines, headlines);
		String answer = callLlm(system, user);
		if (answer == null) {
			failAttempt(ctx, today, attempts, "AI 응답을 받지 못했습니다. 잠시 뒤 다시 시도합니다.");
			return;
		}
		Set<String> candidateSet = new HashSet<>();
		candidates.forEach(c -> candidateSet.add(c.symbol()));
		Set<String> heldSet = new HashSet<>();
		heldLines.forEach(h -> heldSet.add(h.symbol()));
		Decision decision = NewsDecisionParser.parse(answer, candidateSet, heldSet);
		if (decision == null) {
			failAttempt(ctx, today, attempts, "AI 응답을 해석하지 못했습니다. 잠시 뒤 다시 시도합니다.");
			return;
		}

		ctx.state().put("decisionDate", today.toString());
		ctx.state().put("attemptDate", today.toString());
		ctx.state().put("attempts", attempts + 1);
		int minConviction = (int)ctx.num("minConviction", 4);
		List<Pick> strong = decision.buys().stream().filter(p -> p.conviction() >= minConviction).limit(Math.max(0, slots)).toList();
		recordDecision(ctx, today, decision, strong, headlines.size(), record);
		ctx.event(String.format(Locale.ROOT, "뉴스 %d건 분석 · %s", headlines.size(),
			decision.marketView().isBlank() ? "시장 요약 없음" : decision.marketView()));
		if (decision.rejected() > 0) {
			ctx.event("AI 응답 중 규칙에 맞지 않는 항목 " + decision.rejected() + "개는 무시했습니다.");
		}

		Map<String, Candidate> bySymbol = new LinkedHashMap<>();
		candidates.forEach(c -> bySymbol.put(c.symbol(), c));
		if (record) {
			decision.sells().forEach(s -> ctx.event("[기록만] 매도 의견 " + s.symbol() + " · " + s.reason()));
			strong.forEach(p -> ctx.event("[기록만] 매수 의견 " + label(bySymbol.get(p.symbol())) + " · 확신 " + p.conviction() + "/5 · " + p.reason()));
			if (strong.isEmpty()) {
				ctx.event("[기록만] 기준(확신 " + minConviction + " 이상)을 넘는 매수 의견이 없었습니다.");
			}
			return;
		}

		// 4) 매도 의견 실행 → 5) 매수 실행
		for (Sell s : decision.sells()) {
			open.stream().filter(p -> p.getSymbol().equalsIgnoreCase(s.symbol())).findFirst().ifPresent(p -> {
				try {
					SkillExits.sell(ctx, p, "AI 판단 매도: " + s.reason());
				} catch (ApiException e) {
					ctx.event(p.getName() + " 매도하지 못했습니다: " + e.getMessage());
				}
			});
		}
		if (strong.isEmpty()) {
			ctx.event(decision.buys().isEmpty() ? "AI가 오늘은 살 만한 종목이 없다고 판단했습니다."
				: "AI의 매수 의견은 있었지만 확신도가 기준(" + minConviction + "/5)에 못 미쳐 사지 않았습니다.");
			return;
		}
		view = trading.view(ctx.task());
		int free = maxPositions - (int)view.open().stream().filter(p -> (p.getMarket() == Market.KRX) == krx).count();
		List<Pick> buys = strong.stream().limit(Math.max(0, free)).toList();
		if (buys.isEmpty() || view.available().signum() <= 0) {
			ctx.event(buys.isEmpty() ? "보유 종목이 최대(" + maxPositions + "개)라 신규 매수를 하지 않았습니다." : "남은 예산이 없어 신규 매수를 하지 않았습니다.");
			return;
		}
		BigDecimal each = view.available().divide(BigDecimal.valueOf(buys.size()), 2, RoundingMode.DOWN).min(view.limits().positionCap());
		ctx.event("AI가 고른 " + buys.size() + "개에 종목당 최대 " + Currency.valueOf(view.limits().currency()).format(each) + "씩 매수합니다.");
		for (Pick p : buys) {
			Candidate c = bySymbol.get(p.symbol());
			try {
				Plan plan = sv.planner().plan(ctx.account(), p.symbol(), c.market().name(), "BUY", null, each, null);
				String reason = String.format(Locale.ROOT, "AI 뉴스 판단 (확신 %d/5): %s", p.conviction(), p.reason());
				OrderOutcome out = trading.buy(ctx.task(), ctx.account(), plan, reason);
				ctx.outcome(out);
				ctx.event(out.success()
					? String.format(Locale.ROOT, "%s %d주 매수 접수 · %s", plan.name(), plan.quantity(), reason)
					: plan.name() + " 매수 실패: " + out.message());
			} catch (ApiException e) {
				ctx.event(label(c) + " 매수하지 못했습니다: " + e.getMessage());
			}
		}
	}

	private void failAttempt(SkillContext ctx, LocalDate today, int attempts, String message) {
		ctx.state().put("attemptDate", today.toString());
		ctx.state().put("attempts", attempts + 1);
		ctx.event(message);
	}

	/** AI 호출에 시간 제한을 둔다. 시간이 지나면 null(그날 다시 시도). */
	private String callLlm(String system, String user) {
		CompletableFuture<String> f = CompletableFuture.supplyAsync(() -> llm.complete(system, user));
		try {
			return f.get(llmTimeoutSeconds, TimeUnit.SECONDS);
		} catch (TimeoutException e) {
			f.cancel(true);
			log.warn("뉴스 AI 판단 시간 초과({}초)", llmTimeoutSeconds);
			return null;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		} catch (ExecutionException e) {
			log.warn("뉴스 AI 판단 호출 실패: {}", e.getCause() == null ? e.toString() : e.getCause().toString());
			return null;
		}
	}

	/** 판단 기록을 작업 상태에 남긴다(최근 LOG_KEEP 일). 나중에 실제 수익률과 비교해 효과를 확인하는 데 쓴다. */
	@SuppressWarnings("unchecked")
	private void recordDecision(SkillContext ctx, LocalDate today, Decision d, List<Pick> strong, int newsCount, boolean record) {
		Object existing = ctx.state().get("decisionLog");
		List<Object> log = existing instanceof List<?> l ? new ArrayList<>((List<Object>)l) : new ArrayList<>();
		Map<String, Object> entry = new LinkedHashMap<>();
		entry.put("date", today.toString());
		entry.put("mode", record ? "RECORD" : "TRADE");
		entry.put("news", newsCount);
		entry.put("view", d.marketView());
		entry.put("buys", d.buys().stream().map(p -> {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("symbol", p.symbol());
			m.put("conviction", p.conviction());
			m.put("reason", p.reason());
			m.put("acted", strong.contains(p));
			return m;
		}).toList());
		entry.put("sells", d.sells().stream().map(s -> {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("symbol", s.symbol());
			m.put("reason", s.reason());
			return m;
		}).toList());
		log.add(entry);
		while (log.size() > LOG_KEEP) {
			log.remove(0);
		}
		ctx.state().put("decisionLog", log);
	}

	private static String label(Candidate c) {
		return c == null ? "?" : c.name() + "(" + c.symbol() + ")";
	}

	// ─────────────────────────── 입력 처리 ───────────────────────────

	private static String oneOf(Map<String, Object> in, String key, String def, String... allowed) {
		Object v = in.get(key);
		String s = v == null || v.toString().isBlank() ? def : v.toString().trim().toUpperCase(Locale.ROOT);
		if (!Arrays.asList(allowed).contains(s)) {
			throw invalid(key + " 값이 올바르지 않습니다. (" + String.join(", ", allowed) + ")");
		}
		return s;
	}

	private static double bounded(Map<String, Object> in, String key, double def, double min, double max, String label) {
		Object v = in.get(key);
		double d = def;
		if (v != null && !v.toString().isBlank()) {
			try {
				d = v instanceof Number n ? n.doubleValue() : Double.parseDouble(v.toString().replace(",", "").trim());
			} catch (NumberFormatException e) {
				throw invalid(label + " 값이 숫자가 아닙니다.");
			}
		}
		if (Double.isNaN(d) || d < min || d > max) {
			throw invalid(label + "은(는) " + trim(min) + " 이상 " + trim(max) + " 이하여야 합니다.");
		}
		return d;
	}

	private static String trim(double v) {
		return v == Math.rint(v) ? String.valueOf((long)v) : String.valueOf(v);
	}

	static List<String> parseSymbols(String text) {
		if (text == null || text.isBlank()) {
			return List.of();
		}
		return Arrays.stream(text.split("[,\\s]+")).map(String::trim).filter(s -> !s.isEmpty())
			.map(s -> s.toUpperCase(Locale.ROOT)).distinct().toList();
	}

	private static ApiException invalid(String message) {
		return ApiException.of(ErrorCode.AGENT_INVALID, message);
	}
}
