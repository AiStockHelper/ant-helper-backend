package com.backend.domains.agent.skill;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
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
import com.backend.domains.agent.AgentPosition;
import com.backend.domains.agent.AgentTradingService;
import com.backend.domains.agent.AgentTradingService.LedgerView;
import com.backend.domains.agent.Limits;
import com.backend.domains.agent.Schedule;
import com.backend.domains.agent.skill.NewsCandidates.Candidate;
import com.backend.domains.agent.skill.SectorRankParser.Ranking;
import com.backend.domains.chat.ai.LlmGateway;
import com.backend.domains.news.WikiNewsService;
import com.backend.domains.trade.TradePlanner.Plan;
import com.backend.market.Currency;
import com.backend.market.Market;
import com.backend.market.dto.MarketDtos.OrderOutcome;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 뉴스 AI 산업 로테이션 (한국 / 미국).
 * 이 서비스에서 가장 먼저, 가장 꼼꼼하게 과거 시험을 한 방식을 그대로 옮긴 스킬이다.
 * 매달 첫 거래일 장 시작 직후에 지난 한 달의 세계 뉴스 요약을 AI 에게 읽히고, 산업 ETF 전부를 '다음 달에 상대적으로 좋을 순서'로 매기게 한 뒤
 * 상위 N개(기본 3)를 같은 금액씩 사서 한 달 들고 간다. 다음 달에 순위가 바뀌면 빠진 ETF 는 팔고 새로 들어온 ETF 를 산다.
 * 개별 종목이 아니라 산업 ETF 이고, 손절·익절은 없다(과거 시험과 같게). 누적 손실 한도만 안전장치로 둔다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NewsSectorRotationSkill implements Skill {

	public static final String ID = "news-sector-rotation";
	static final int MAX_ATTEMPTS_PER_DAY = 3;
	/** 이 날짜(월 초 N일)까지 리밸런싱하지 못하면 그 달은 건너뛴다(뒤늦게 한 달 늦은 뉴스로 매매하지 않도록). */
	static final int LAST_DAY_TO_START = 10;
	static final int LOG_KEEP = 24;

	private final WikiNewsService wiki;
	private final LlmGateway llm;

	/** 테스트에서 줄일 수 있도록 분리해 둔 AI 응답 대기 시간 */
	long llmTimeoutSeconds = 120;

	@Override
	public String id() {
		return ID;
	}

	@Override
	public String name() {
		return "뉴스 AI 산업 로테이션";
	}

	@Override
	public String summary() {
		return "매달 지난 한 달 뉴스를 AI가 읽고 유망한 산업 ETF 상위 3개를 골라 한 달씩 보유합니다. 한국·미국을 따로 운용합니다.";
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
			매달 첫 거래일 장이 열리면 **지난 한 달 동안의 세계 뉴스 요약**(위키피디아 일자별 뉴스 중 경제·과학기술·국제관계·분쟁·정치 등)을 \
			AI가 읽고, 산업 ETF 전부를 **다음 달에 다른 ETF보다 좋을 것 같은 순서**로 매깁니다. 상위 N개(기본 3개)를 **같은 금액씩 사서 한 달 동안 보유**하고, \
			다음 달 순위가 바뀌면 빠진 ETF는 팔고 새로 들어온 ETF를 삽니다. 한국(KODEX/TIGER 업종 ETF 16종)과 미국(SPDR 섹터 ETF 11종)은 **작업을 따로 만들어** 운용합니다.

			이 서비스가 과거 데이터로 가장 먼저 시험한 방식을 그대로 옮긴 것입니다. 개별 종목을 고르지 않고 **산업을 고르며, 손절·익절 없이 한 달씩** 들고 갑니다.

			## 하루 흐름
			1. 평일 장이 열린 5분 뒤에 깨어나지만, **달이 바뀐 뒤 첫 실행에서만** 판단합니다(한 달에 1회, AI 호출도 한 달에 1회). 다른 날은 누적 손실 한도만 확인합니다.
			2. 뉴스를 받지 못하거나 AI 응답을 읽지 못하면 다음 거래일에 다시 시도하고, **월 초 10일까지 못 하면 그 달은 건너뜁니다**(한 달 늦은 뉴스로 매매하지 않기 위해).
			3. 순위에서 빠진 ETF는 팔고, 새로 상위에 든 ETF는 시드를 나눈 금액만큼 삽니다(이미 들고 있는 상위 ETF는 그대로 둡니다).
			4. 누적 손실이 한도에 닿으면 전량 정리하고 스스로 멈춥니다. **'기록만' 모드**로 두면 주문 없이 AI의 순위만 매달 기록합니다.

			## 과거 시험 결과 (반드시 읽어 주세요)
			- **미국**: AI의 지식 시점 이후 15개월(2025-07~2026-10)에서 이 방식은 연 환산 +37.8%, 같은 기간 S&P 500(SPY)은 +19.6%였습니다. 뉴스 없이 날짜만 준 대조군은 +24.6%였습니다. \
			2026년만 보면 +33% 대 +12%였고, 2026년 3월 중동 전쟁 때 에너지 섹터를 앞서 골랐습니다. 다만 **판단 횟수가 15번뿐이라 우연일 가능성을 배제할 수 없습니다.**
			- **한국**: 뉴스 효과가 **없었습니다.** 뉴스를 읽은 쪽(+87.3%)과 뉴스 없는 대조군(+93.3%), 단순 가격 모멘텀(+77.6%)이 비슷했고, 모두 같은 기간 급등한 반도체·IT 쏠림이었습니다. \
			한국에서 쓰시더라도 '뉴스를 읽어서 얻는 수익'은 기대하지 마세요.
			- 2016~2026 전체 구간 결과는 **AI가 과거를 이미 알고 있어서 믿을 수 없습니다**(뉴스 없이 날짜만 줘도 연 40%대가 나왔습니다).
			- 시험은 이 서비스의 AI가 아니라 다른 AI 모델로 했습니다. 서비스의 AI(GPT-4.1, 실패 시 Claude)는 순위를 다르게 매길 수 있습니다.

			## 반드시 알아두세요
			- **수익을 보장하지 않습니다.** 항상 시드 전부를 산업 ETF에 투자하므로 시장이 빠지면 같이 빠집니다. 시험에서 최대 낙폭은 미국 -7%, 한국 -32~-37%였습니다.
			- 손절·익절이 없습니다. 안전장치는 **누적 손실 한도**(기본 25%)뿐입니다.
			- 뉴스는 **위키피디아의 세계 정세 요약**이라 기업·산업 뉴스가 얇고, 사후에 편집될 수 있습니다.
			- 수수료·거래세·슬리피지는 손익 계산에 포함하지 않습니다. 미국 ETF는 시장가 주문이 불가능해 현재가에서 약간 위/아래의 지정가로 주문하므로 체결이 안 될 수 있습니다.
			- 이 스킬이 산 종목만 관리하고 계좌의 다른 보유 종목은 건드리지 않습니다.
			- **실전 계좌에서는 시작할 때 한 번 승인이 필요**합니다. **'기록만' 모드 또는 모의 계좌와 작은 시드로 몇 달 지켜본 뒤** 쓰세요.
			""";
	}

	@Override
	public List<Param> params() {
		return List.of(
			Param.select("market", "시장", "US", List.of(new Option("KRX", "한국 (원)"), new Option("US", "미국 (달러)")),
				"한국과 미국은 작업을 따로 만들어 각각 운용하세요. 과거 시험에서 뉴스 효과가 보인 쪽은 미국이었습니다."),
			Param.select("mode", "동작 방식", "TRADE", List.of(new Option("TRADE", "실제 매매"), new Option("RECORD", "기록만 (주문 안 함)")),
				"'기록만'은 AI가 매긴 산업 순위만 매달 남기고 주문하지 않습니다."),
			Param.number("budget", "시드(투자 한도)", 3_000, 100, 1_000_000_000_000.0, "원/달러",
				"이 스킬이 굴릴 수 있는 최대 금액입니다. 한국은 원, 미국은 달러 단위로 입력하세요."),
			Param.number("topN", "보유할 산업 수", 3, 1, 5, "개", "AI 순위 상위 몇 개를 같은 금액씩 살지 정합니다. 과거 시험은 3개였습니다."),
			Param.number("maxLossPct", "누적 손실 한도", 25, 5, 50, "%", "실현+평가 손실이 시드의 이 비율에 닿으면 전량 매도하고 자동매매를 멈춥니다. 손절선이 없는 대신 두는 안전장치입니다."));
	}

	@Override
	public Map<String, Object> normalize(Map<String, Object> in) {
		Map<String, Object> input = in == null ? Map.of() : in;
		String market = String.valueOf(input.getOrDefault("market", "US")).trim().toUpperCase(Locale.ROOT);
		if (!market.equals("KRX") && !market.equals("US")) {
			throw invalid("시장은 KRX 또는 US 여야 합니다.");
		}
		boolean krx = market.equals("KRX");
		Map<String, Object> c = new LinkedHashMap<>();
		c.put("market", market);
		Object m = input.get("mode");
		String mode = m == null || m.toString().isBlank() ? "TRADE" : m.toString().trim().toUpperCase(Locale.ROOT);
		if (!mode.equals("TRADE") && !mode.equals("RECORD")) {
			throw invalid("동작 방식은 TRADE 또는 RECORD 여야 합니다.");
		}
		c.put("mode", mode);
		c.put("budget", bounded(input, "budget", krx ? 5_000_000 : 3_000, krx ? 100_000 : 100, 1e12, "시드"));
		c.put("topN", (int)bounded(input, "topN", 3, 1, 5, "보유할 산업 수"));
		c.put("maxLossPct", bounded(input, "maxLossPct", 25, 5, 50, "누적 손실 한도"));
		return c;
	}

	@Override
	public Schedule schedule(Map<String, Object> config) {
		return Schedule.marketEvent((String)config.get("market"), "OPEN", 5);
	}

	@Override
	public Limits limits(Map<String, Object> config) {
		boolean krx = "KRX".equals(config.get("market"));
		BigDecimal budget = BigDecimal.valueOf(((Number)config.get("budget")).doubleValue()).setScale(krx ? 0 : 2, RoundingMode.DOWN);
		int n = ((Number)config.get("topN")).intValue();
		return new Limits(budget, krx ? "KRW" : "USD", 100.0 / n, budget, ((Number)config.get("maxLossPct")).doubleValue());
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
		Market group = "US".equals(ctx.str("market", "US")) ? Market.NASDAQ : Market.KRX;
		run(ctx, ZonedDateTime.now(group.zone()));
	}

	/** 시각을 받는 실행(테스트에서 시계를 고정하려고 분리). */
	void run(SkillContext ctx, ZonedDateTime now) {
		boolean krx = !"US".equals(ctx.str("market", "US"));
		Market group = krx ? Market.KRX : Market.NASDAQ;
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
		if (!record) {
			if (!today.toString().equals(ctx.stateStr("reconciledDate"))) {
				for (String line : trading.reconcile(ctx.task(), ctx.account())) {
					ctx.event(line);
				}
				ctx.state().put("reconciledDate", today.toString());
			}
			if (SkillExits.enforceLossLimit(ctx)) {
				return;
			}
		}

		YearMonth month = YearMonth.from(today);
		if (month.toString().equals(ctx.stateStr("rebalancedMonth"))) {
			if (ctx.manual()) {
				ctx.event("이번 달(" + month + ")에는 이미 산업 순위를 매겼습니다. 다음 달 첫 거래일에 다시 판단합니다.");
			}
			return;
		}
		if (today.getDayOfMonth() > LAST_DAY_TO_START) {
			ctx.state().put("rebalancedMonth", month.toString());
			ctx.event(month + " 월 초 " + LAST_DAY_TO_START + "일까지 순위를 매기지 못해 이번 달은 건너뜁니다. 보유 ETF는 그대로 둡니다.");
			return;
		}
		int attempts = today.toString().equals(ctx.stateStr("attemptDate")) && ctx.state().get("attempts") instanceof Number n ? n.intValue() : 0;
		if (attempts >= MAX_ATTEMPTS_PER_DAY) {
			if (ctx.manual()) {
				ctx.event("오늘은 이미 " + MAX_ATTEMPTS_PER_DAY + "번 시도했습니다. 다음 거래일에 다시 시도합니다.");
			}
			return;
		}

		YearMonth newsMonth = month.minusMonths(1);
		String digest = wiki.monthlyDigest(newsMonth);
		if (digest == null) {
			failAttempt(ctx, today, attempts, newsMonth + " 월 뉴스 요약을 가져오지 못했습니다. 다음에 다시 시도합니다.");
			return;
		}
		int topN = (int)ctx.num("topN", 3);
		List<Candidate> etfs = NewsCandidates.referenceEtfs(sv.marketData(), krx);
		if (etfs.size() < Math.max(4, topN + 1)) {
			failAttempt(ctx, today, attempts, "종목 마스터에서 확인된 산업 ETF가 " + etfs.size() + "개뿐이라 순위를 매기지 않았습니다.");
			return;
		}
		String answer = callLlm(SectorRotationPrompt.system(), SectorRotationPrompt.user(newsMonth, etfs, krx, digest));
		if (answer == null) {
			failAttempt(ctx, today, attempts, "AI 응답을 받지 못했습니다. 다음에 다시 시도합니다.");
			return;
		}
		Map<String, Candidate> bySymbol = new LinkedHashMap<>();
		etfs.forEach(c -> bySymbol.put(c.symbol(), c));
		Ranking ranking = SectorRankParser.parse(answer, bySymbol.keySet());
		if (ranking == null || ranking.order().size() < topN) {
			failAttempt(ctx, today, attempts, "AI 응답에서 산업 순위를 충분히 읽지 못했습니다. 다음에 다시 시도합니다.");
			return;
		}

		List<String> targets = ranking.order().subList(0, topN);
		ctx.state().put("rebalancedMonth", month.toString());
		ctx.state().put("attemptDate", today.toString());
		ctx.state().put("attempts", attempts + 1);
		recordRanking(ctx, month, newsMonth, ranking, targets, record);
		ctx.event(newsMonth + " 뉴스로 산업 순위를 매겼습니다 · 상위 " + topN + ": " + describe(targets, bySymbol)
			+ (ranking.rationale().isBlank() ? "" : " · " + ranking.rationale()));

		if (record) {
			ctx.event("[기록만] 순위만 기록하고 주문하지 않았습니다.");
			return;
		}
		rebalance(ctx, group, krx, targets, bySymbol, topN);
	}

	private void rebalance(SkillContext ctx, Market group, boolean krx, List<String> targets, Map<String, Candidate> bySymbol, int topN) {
		SkillContext.Services sv = ctx.services();
		AgentTradingService trading = sv.trading();
		Set<String> targetSet = new HashSet<>(targets);
		LedgerView view = trading.view(ctx.task());
		for (AgentPosition p : view.open()) {
			if ((p.getMarket() == Market.KRX) != krx || targetSet.contains(p.getSymbol().toUpperCase(Locale.ROOT))) {
				continue;
			}
			try {
				SkillExits.sell(ctx, p, "산업 로테이션: 이번 달 상위 " + topN + "개에서 빠짐");
			} catch (ApiException e) {
				ctx.event(p.getName() + " 매도하지 못했습니다: " + e.getMessage());
			}
		}
		view = trading.view(ctx.task());
		Set<String> held = new HashSet<>();
		view.open().stream().filter(p -> (p.getMarket() == Market.KRX) == krx).forEach(p -> held.add(p.getSymbol().toUpperCase(Locale.ROOT)));
		List<String> toBuy = targets.stream().filter(s -> !held.contains(s)).limit(Math.max(0, topN - held.size())).toList();
		if (toBuy.isEmpty()) {
			ctx.event("이미 이번 달 상위 " + topN + "개를 모두 보유 중이라 새로 사지 않았습니다.");
			return;
		}
		if (view.available().signum() <= 0) {
			ctx.event("남은 예산이 없어 새로 사지 못했습니다.");
			return;
		}
		BigDecimal each = view.available().divide(BigDecimal.valueOf(toBuy.size()), 2, RoundingMode.DOWN).min(view.limits().positionCap());
		ctx.event("상위 ETF " + toBuy.size() + "개를 종목당 최대 " + Currency.valueOf(view.limits().currency()).format(each) + "씩 매수합니다.");
		for (String symbol : toBuy) {
			Candidate c = bySymbol.get(symbol);
			try {
				Plan plan = sv.planner().plan(ctx.account(), symbol, c.market().name(), "BUY", null, each, null);
				String reason = "산업 로테이션: " + (c.theme().isBlank() ? c.name() : c.theme()) + " (이번 달 상위 " + topN + "개)";
				OrderOutcome out = trading.buy(ctx.task(), ctx.account(), plan, reason);
				ctx.outcome(out);
				ctx.event(out.success()
					? String.format(Locale.ROOT, "%s %d주 매수 접수 · %s", plan.name(), plan.quantity(), reason)
					: plan.name() + " 매수 실패: " + out.message());
			} catch (ApiException e) {
				ctx.event(c.name() + "(" + symbol + ") 매수하지 못했습니다: " + e.getMessage());
			}
		}
	}

	private static String describe(List<String> codes, Map<String, Candidate> bySymbol) {
		List<String> out = new ArrayList<>();
		for (String s : codes) {
			Candidate c = bySymbol.get(s);
			out.add(c == null ? s : s + "(" + (c.theme().isBlank() ? c.name() : c.theme()) + ")");
		}
		return String.join(", ", out);
	}

	private void failAttempt(SkillContext ctx, LocalDate today, int attempts, String message) {
		ctx.state().put("attemptDate", today.toString());
		ctx.state().put("attempts", attempts + 1);
		ctx.event(message);
	}

	/** AI 호출에 시간 제한을 둔다. 시간이 지나면 null(다음에 다시 시도). */
	private String callLlm(String system, String user) {
		CompletableFuture<String> f = CompletableFuture.supplyAsync(() -> llm.complete(system, user));
		try {
			return f.get(llmTimeoutSeconds, TimeUnit.SECONDS);
		} catch (TimeoutException e) {
			f.cancel(true);
			log.warn("산업 로테이션 AI 판단 시간 초과({}초)", llmTimeoutSeconds);
			return null;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		} catch (ExecutionException e) {
			log.warn("산업 로테이션 AI 호출 실패: {}", e.getCause() == null ? e.toString() : e.getCause().toString());
			return null;
		}
	}

	/** 매달의 AI 순위를 작업 상태에 남긴다(최근 LOG_KEEP 개월). 나중에 실제 수익률과 비교하는 데 쓴다. */
	@SuppressWarnings("unchecked")
	private void recordRanking(SkillContext ctx, YearMonth month, YearMonth newsMonth, Ranking r, List<String> targets, boolean record) {
		Object existing = ctx.state().get("rankingLog");
		List<Object> log = existing instanceof List<?> l ? new ArrayList<>((List<Object>)l) : new ArrayList<>();
		Map<String, Object> entry = new LinkedHashMap<>();
		entry.put("month", month.toString());
		entry.put("newsMonth", newsMonth.toString());
		entry.put("mode", record ? "RECORD" : "TRADE");
		entry.put("ranking", r.order());
		entry.put("targets", targets);
		entry.put("rationale", r.rationale());
		log.add(entry);
		while (log.size() > LOG_KEEP) {
			log.remove(0);
		}
		ctx.state().put("rankingLog", log);
	}

	// ─────────────────────────── 입력 처리 ───────────────────────────

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
