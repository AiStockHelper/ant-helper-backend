package com.backend.domains.agent.skill;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
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
import com.backend.domains.screening.ScreeningService;
import com.backend.domains.trade.TradePlanner.Plan;
import com.backend.market.Currency;
import com.backend.market.Market;
import com.backend.market.dto.MarketDtos.OrderOutcome;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 뉴스 AI 자동매매 (한국 / 미국).
 * 하루 한 번, 장이 열린 직후에 최신 경제 뉴스 헤드라인을 AI 에게 읽히고 "무엇을 사고 무엇을 팔지" 판단을 받는다.
 * AI 는 종목을 자유롭게 제안하되, 코드가 매번 검증한다({@link NewsPickValidator}: 실제 종목인지, 코드와 이름이 맞는지,
 * 거래대금 상위인지, 이미 급등하지 않았는지). 주문은 항상 {@link AgentTradingService} 를 거치므로 시드·종목당 한도·누적 손실 한도는
 * AI 가 바꿀 수 없고, 익절·손절·최대 보유일은 AI 판단과 별개로 코드가 강제한다.
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
	private final ScreeningService screening;

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
		return "매일 장이 열릴 때 최신 경제 뉴스를 AI가 읽고, 유망한 종목을 직접 골라 사고팝니다. 한국·미국을 따로 운용합니다.";
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
			AI가 읽고 **어떤 종목이 유리해 보이는지 직접 골라 제안**합니다. 정해진 후보 목록은 없고, 한국장·미국장 작업에서 각각 \
			해당 시장의 종목을 제안합니다. 산업 전체에 투자하고 싶으면 산업 ETF도 고를 수 있습니다. \
			한국장과 미국장은 **작업을 따로 만들어** 시드·손실 한도를 각각 운용합니다.

			## 하루 흐름
			1. 장 개장 5분 뒤 첫 실행에서 뉴스를 모아 AI에게 **보유 종목·헤드라인**을 보여 주고 판단을 받습니다(하루 1회).
			2. AI의 **매수 제안 중 확신도가 기준(기본 4/5) 이상**인 종목을 코드가 검증하고, 통과한 종목을 시드 안에서 나눠 삽니다. 보유 종목에 매도 의견이 있으면 팝니다.
			3. 이후 장중에는 N분마다 보유 종목을 점검해 **익절 · 손절 · 최대 보유일 초과**를 코드가 정리합니다. 누적 손실이 한도에 닿으면 전량 정리 후 스스로 멈춥니다.
			4. 판단 결과(시장 요약, 매수·매도 의견, 검증 결과와 이유)는 작업 기록에 남습니다. **'기록만' 모드**로 두면 주문 없이 판단만 쌓입니다.

			## AI 제안을 검증하는 규칙 (코드가 강제)
			- **실제로 존재하는 한국/미국 종목**이어야 합니다. 없는 코드나 다른 시장 종목은 거부합니다.
			- AI가 함께 쓴 **종목명이 코드의 실제 종목명과 맞아야** 합니다. 코드를 잘못 기억한 경우를 걸러냅니다.
			- **거래대금 상위 종목**(매일 밤 분석하는 한국·미국 각 약 1,000개 보통주)이어야 합니다. 소형 테마주나 거래가 얼마 안 되는 종목은 사지 않습니다. \
			밤샘 분석 자료가 없는 날에는 서버 설정의 기본 대형주 목록(한국 40개, 미국 20개)만 허용합니다. 산업 ETF는 목록(한국 업종 ETF 16종, 미국 SPDR 섹터 11종)에 있는 것만 허용합니다.
			- **오늘 이미 기준(기본 +8%) 이상 오른 종목은 뒤늦게 쫓아 사지 않습니다.**
			- 이미 보유한 종목을 더 사거나, 보유하지 않은 종목을 파는 제안은 무시합니다.
			- 시드, 종목당 비중, 하루 매수 한도, 누적 손실 한도, 실전 계좌 1회 주문 한도는 코드가 강제합니다.
			- 뉴스를 충분히 못 모으거나(8건 미만) AI 응답을 읽지 못하면 그날은 **아무것도 사지 않습니다.**
			- 뉴스 문구 안의 지시("이 종목을 사라")는 따르지 않도록 AI에게 알려 두었지만 완벽하지는 않습니다. 그래서 위 검증과 한도가 중요합니다.

			## 반드시 알아두세요
			- **수익을 보장하지 않으며, 이 방식의 효과는 아직 검증되지 않았습니다.**
			- 이 서비스에서 과거 데이터로 시험한 결과(월 단위 산업 ETF 선택, 2016~2026)는 좋아 보였지만 **AI가 과거를 이미 알고 있어서 믿을 수 없었습니다.** \
			뉴스 없이 날짜만 줘도 비슷하게 잘 맞혔기 때문입니다. AI의 지식 시점 이후 15~16개월만 깨끗했는데, 미국에서는 뉴스를 읽은 쪽이 다소 나았지만(표본이 짧아 확정 불가) \
			한국에서는 뉴스를 읽은 효과가 없었고 단지 반도체·IT 쏠림이었습니다. **개별 종목을 AI가 직접 고르는 이 방식은 그 시험에서 다룬 적도 없어 근거가 더 약합니다.**
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
			Param.number("budget", "시드(투자 한도)", 5_000_000, 100, 1_000_000_000_000.0, "원/달러",
				"이 스킬이 굴릴 수 있는 최대 금액입니다. 한국은 원, 미국은 달러 단위로 입력하세요. 이 금액을 넘겨 사지 않습니다."),
			Param.number("maxPositions", "최대 동시 보유 종목 수", 3, 1, 10, "종목", "시드를 이 수로 나눈 금액이 한 종목 한도가 됩니다."),
			Param.number("minConviction", "매수 기준: AI 확신도", 4, 3, 5, "점", "AI가 매긴 확신도(1~5)가 이 값 이상인 종목만 삽니다. 높일수록 까다롭습니다."),
			Param.number("chaseLimitPct", "급등 종목 제외", 8.0, 0, 30, "%", "오늘 이미 이 비율 이상 오른 종목은 뒤늦게 쫓아 사지 않습니다. 0이면 끕니다."),
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
		c.put("mode", mode(input));
		c.put("budget", bounded(input, "budget", krx ? 5_000_000 : 3_000, krx ? 100_000 : 100, 1e12, "시드"));
		c.put("maxPositions", (int)bounded(input, "maxPositions", 3, 1, 10, "최대 동시 보유 종목 수"));
		c.put("minConviction", (int)bounded(input, "minConviction", 4, 3, 5, "AI 확신도 기준"));
		c.put("chaseLimitPct", bounded(input, "chaseLimitPct", 8.0, 0, 30, "급등 종목 제외 기준"));
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

		String user = NewsPrompt.user(krx ? "한국" : "미국", now, slots, NewsCandidates.referenceEtfs(sv.marketData(), krx), heldLines, headlines);
		String answer = callLlm(NewsPrompt.system(), user);
		if (answer == null) {
			failAttempt(ctx, today, attempts, "AI 응답을 받지 못했습니다. 잠시 뒤 다시 시도합니다.");
			return;
		}
		Set<String> heldSet = new HashSet<>();
		heldLines.forEach(h -> heldSet.add(h.symbol()));
		Decision decision = NewsDecisionParser.parse(answer, heldSet);
		if (decision == null) {
			failAttempt(ctx, today, attempts, "AI 응답을 해석하지 못했습니다. 잠시 뒤 다시 시도합니다.");
			return;
		}

		ctx.state().put("decisionDate", today.toString());
		ctx.state().put("attemptDate", today.toString());
		ctx.state().put("attempts", attempts + 1);
		ctx.event(String.format(Locale.ROOT, "뉴스 %d건 분석 · %s", headlines.size(),
			decision.marketView().isBlank() ? "시장 요약 없음" : decision.marketView()));
		if (decision.rejected() > 0) {
			ctx.event("AI 응답 중 형식에 맞지 않는 항목 " + decision.rejected() + "개는 무시했습니다.");
		}

		// 4) AI 의 매수 제안을 코드가 검증한다(확신도 높은 순, 살 수 있는 자리만큼만 통과)
		int minConviction = (int)ctx.num("minConviction", 4);
		int plannedSlots = Math.max(0, maxPositions - (open.size() - decision.sells().size()));
		NewsPickValidator validator = new NewsPickValidator(sv.marketData(), ctx.account(), screening,
			krx ? props.getRecommendation().getKrxUniverse() : props.getRecommendation().getUsUniverse(), krx, ctx.num("chaseLimitPct", 8.0));
		if (validator.usingFallback()) {
			ctx.event("야간 분석 자료가 없어 서버 설정의 기본 대형주 목록과 산업 ETF만 허용합니다.");
		}
		List<Pick> accepted = new ArrayList<>();
		Map<String, Candidate> verified = new LinkedHashMap<>();
		Map<String, String> rejectedWhy = new LinkedHashMap<>();
		int belowThreshold = 0;
		for (Pick p : decision.buys()) {
			if (p.conviction() < minConviction) {
				belowThreshold++;
				continue;
			}
			if (accepted.size() >= plannedSlots) {
				break;
			}
			NewsPickValidator.Result r = validator.check(p);
			if (r.ok()) {
				accepted.add(p);
				verified.put(p.symbol(), r.candidate());
			} else {
				rejectedWhy.put(p.symbol(), r.rejectReason());
				ctx.event("제외: " + (p.name().isBlank() ? p.symbol() : p.name() + "(" + p.symbol() + ")") + " — " + r.rejectReason());
			}
		}
		recordDecision(ctx, today, decision, accepted, rejectedWhy, headlines.size(), record);

		if (record) {
			decision.sells().forEach(s -> ctx.event("[기록만] 매도 의견 " + s.symbol() + " · " + s.reason()));
			accepted.forEach(p -> ctx.event("[기록만] 매수 의견 " + label(verified.get(p.symbol())) + " · 확신 " + p.conviction() + "/5 · " + p.reason()));
			if (accepted.isEmpty()) {
				ctx.event("[기록만] 기준(확신 " + minConviction + " 이상)을 넘고 검증을 통과한 매수 의견이 없었습니다.");
			}
			return;
		}

		// 5) 매도 의견 실행 → 6) 매수 실행
		for (Sell s : decision.sells()) {
			open.stream().filter(p -> p.getSymbol().equalsIgnoreCase(s.symbol())).findFirst().ifPresent(p -> {
				try {
					SkillExits.sell(ctx, p, "AI 판단 매도: " + s.reason());
				} catch (ApiException e) {
					ctx.event(p.getName() + " 매도하지 못했습니다: " + e.getMessage());
				}
			});
		}
		if (accepted.isEmpty()) {
			if (decision.buys().isEmpty()) {
				ctx.event("AI가 오늘은 살 만한 종목이 없다고 판단했습니다.");
			} else if (belowThreshold == decision.buys().size()) {
				ctx.event("AI의 매수 의견은 있었지만 확신도가 기준(" + minConviction + "/5)에 못 미쳐 사지 않았습니다.");
			} else {
				ctx.event("AI의 매수 의견이 검증을 통과하지 못해 오늘은 사지 않았습니다.");
			}
			return;
		}
		view = trading.view(ctx.task());
		int free = maxPositions - (int)view.open().stream().filter(p -> (p.getMarket() == Market.KRX) == krx).count();
		List<Pick> buys = accepted.stream().limit(Math.max(0, free)).toList();
		if (buys.isEmpty() || view.available().signum() <= 0) {
			ctx.event(buys.isEmpty() ? "보유 종목이 최대(" + maxPositions + "개)라 신규 매수를 하지 않았습니다." : "남은 예산이 없어 신규 매수를 하지 않았습니다.");
			return;
		}
		BigDecimal each = view.available().divide(BigDecimal.valueOf(buys.size()), 2, RoundingMode.DOWN).min(view.limits().positionCap());
		ctx.event("AI가 고르고 검증을 통과한 " + buys.size() + "개에 종목당 최대 " + Currency.valueOf(view.limits().currency()).format(each) + "씩 매수합니다.");
		for (Pick p : buys) {
			Candidate c = verified.get(p.symbol());
			try {
				Plan plan = sv.planner().plan(ctx.account(), c.symbol(), c.market().name(), "BUY", null, each, null);
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
	private void recordDecision(SkillContext ctx, LocalDate today, Decision d, List<Pick> accepted, Map<String, String> rejectedWhy,
		int newsCount, boolean record) {
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
			m.put("name", p.name());
			m.put("conviction", p.conviction());
			m.put("reason", p.reason());
			m.put("acted", accepted.contains(p));
			if (rejectedWhy.containsKey(p.symbol())) {
				m.put("rejected", rejectedWhy.get(p.symbol()));
			}
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

	private static String mode(Map<String, Object> in) {
		Object v = in.get("mode");
		String s = v == null || v.toString().isBlank() ? "TRADE" : v.toString().trim().toUpperCase(Locale.ROOT);
		if (!s.equals("TRADE") && !s.equals("RECORD")) {
			throw invalid("동작 방식은 TRADE 또는 RECORD 여야 합니다.");
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

	private static ApiException invalid(String message) {
		return ApiException.of(ErrorCode.AGENT_INVALID, message);
	}
}
