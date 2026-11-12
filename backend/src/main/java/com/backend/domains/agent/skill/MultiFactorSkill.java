package com.backend.domains.agent.skill;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.backend.common.exception.ApiException;
import com.backend.common.exception.ErrorCode;
import com.backend.domains.agent.AgentTradingService;
import com.backend.domains.agent.AgentTradingService.LedgerView;
import com.backend.domains.agent.Limits;
import com.backend.domains.agent.Schedule;
import com.backend.domains.agent.skill.MultiFactorScorer.Filters;
import com.backend.domains.agent.skill.MultiFactorScorer.Input;
import com.backend.domains.agent.skill.MultiFactorScorer.Result;
import com.backend.domains.agent.skill.MultiFactorScorer.Weights;
import com.backend.domains.factor.FactorSnapshot;
import com.backend.domains.factor.FactorSnapshotRepository;
import com.backend.domains.trade.TradePlanner.Plan;
import com.backend.market.Currency;
import com.backend.market.FactorDataService;
import com.backend.market.FactorDataService.FlowSummary;
import com.backend.market.FactorDataService.Valuation;
import com.backend.market.Market;
import com.backend.market.dto.MarketDtos.OrderOutcome;
import com.backend.market.dto.MarketDtos.Recommendation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 멀티팩터 자동매매 (한국).
 * 야간 스크리닝의 기술 요인 순위 + 외국인·기관 수급 + 밸류에이션(PER/PBR)을 합쳐 종합 점수를 매기고,
 * 기준 이상인 종목을 시드 안에서 나눠 산 뒤 목표 수익·손절·보유일·누적 손실 한도로 정리한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MultiFactorSkill implements Skill {

	public static final String ID = "multi-factor-trader";
	private static final String KOSPI = "0001";

	private final FactorDataService factors;
	private final FactorSnapshotRepository snapshots;

	@Override
	public String id() {
		return ID;
	}

	@Override
	public String name() {
		return "멀티팩터 자동매매 (기술·수급·가치)";
	}

	@Override
	public String summary() {
		return "가격 흐름, 외국인·기관 수급, PER/PBR을 함께 점수로 매겨 높은 종목을 사고, 목표·손절 규칙으로 자동 정리합니다.";
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
			트레이더들이 흔히 보는 정보를 한 번에 점수로 합쳐 종목을 고릅니다. 한국 주식 전용입니다.

			| 요인 | 보는 것 | 기본 비중 |
			|---|---|---|
			| 기술 | 변동성·과매도·단기 눌림 등 가격·거래량 요인 순위 (매일 밤 분석) | 60 |
			| 수급 | 최근 며칠간 외국인+기관 순매수가 거래대금에서 차지하는 비중, 동반 매수 여부 | 30 |
			| 가치 | PER·PBR (낮을수록 높은 점수, 적자는 감점) | 10 |

			종합 점수는 -100~100 입니다. **기준 점수 이상**인 종목만, 비중 큰 순서로 **시드 안에서 나눠 매수**합니다. \
			보유 종목은 **목표 수익률 · 손절선 · 최대 보유일**에서 정리하고, **누적 손실 한도**에 닿으면 전량 정리 후 스스로 멈춥니다. \
			코스피가 하루에 크게 빠진 날(시장 급락 기준)은 신규 매수를 쉽니다.

			## 하루 흐름
			1. 장이 열리면 개장 5분 뒤부터 장중 N분마다 깨어납니다. 휴장일은 건너뜁니다.
			2. 깨어날 때마다 보유 종목의 현재가를 확인해 익절·손절·보유일 초과를 처리합니다.
			3. 그날 첫 실행에서 후보 종목의 수급과 PER/PBR을 조회해 점수를 매기고 신규 매수합니다(하루 1회).
			4. 평가한 종목의 요인 값은 기록해 둡니다(이후 효과 검증용). 주문과 중요한 일은 이 작업의 기록에 남습니다.

			## 반드시 알아두세요
			- **수익을 보장하지 않습니다.** 점수는 확률을 약간 높이려는 참고 지표일 뿐입니다.
			- **기술 요인만 과거 데이터로 검증했습니다.** 이 서비스의 검증에서도 추세·모멘텀이 오히려 불리했고, 변동성이 낮거나 단기로 눌린 종목이 약하게 유리했습니다(차이는 작았습니다).
			- **수급·가치 요인은 아직 과거 데이터로 검증하지 못했습니다.** 과거 수급·밸류에이션 자료를 구하지 못해서입니다. 이 스킬이 평가한 값은 계속 기록해 두므로 몇 주 쌓이면 실제 효과를 확인할 수 있습니다. 그전까지는 기본 비중(기술 60 · 수급 30 · 가치 10)을 보수적으로 두었습니다.
			- PER·PBR은 **업종과 비교하지 않습니다.** 성장주·금융주처럼 업종 특성이 다른 종목은 점수가 어색할 수 있습니다.
			- 수급은 **전일까지 확정된 값**이며, 실시간 수급이 아닙니다.
			- **모의투자 서버는 수급·지수 조회를 지원하지 않을 수 있습니다.** 그 경우 수급 점수는 0으로 처리되고, '수급 필수'를 켜 두면 매수하지 못합니다. 모의 계좌에서 먼저 동작을 확인하세요.
			- 수수료·거래세·슬리피지는 손익 계산에 포함하지 않습니다. 잦은 매매는 비용으로 손실이 날 수 있습니다.
			- 목표/손절은 체결가가 아니라 N분마다 확인한 현재가 기준이라, 급변동 때는 실제 체결가와 차이가 납니다.
			- 이 스킬이 산 종목만 관리하고 계좌의 다른 보유 종목은 건드리지 않습니다.
			- **실전 계좌에서는 시작할 때 한 번 승인이 필요**하고, 이후에는 정해 둔 시드·손실 한도 안에서만 주문합니다. **먼저 모의 계좌와 작은 시드로 몇 주 확인**한 뒤 쓰세요.
			""";
	}

	@Override
	public List<Param> params() {
		List<Option> onOff = List.of(new Option("ON", "켬"), new Option("OFF", "끔"));
		return List.of(
			Param.number("budget", "시드(투자 한도)", 5_000_000, 100_000, 1_000_000_000_000.0, "원",
				"이 스킬이 굴릴 수 있는 최대 금액입니다. 이 금액을 넘겨 사지 않습니다."),
			Param.number("maxPositions", "최대 동시 보유 종목 수", 5, 1, 20, "종목", "시드를 이 수로 나눈 금액이 한 종목 한도가 됩니다."),
			Param.number("minScore", "매수 기준 점수", 20, 0, 100, "점", "종합 점수가 이 값 이상인 종목만 삽니다. 높일수록 까다롭게 고릅니다."),
			Param.number("candidates", "분석할 후보 수", 15, 5, 30, "종목", "기술 요인 상위 종목 중 수급·가치를 조회할 후보 수입니다. 많을수록 증권사 조회가 늘어 느려집니다."),
			Param.number("wTech", "비중: 기술", 60, 0, 100, "", "가격·거래량 요인의 비중입니다. 세 비중의 합이 달라도 비율로 계산됩니다."),
			Param.number("wFlow", "비중: 수급", 30, 0, 100, "", "외국인·기관 수급의 비중입니다. 아직 효과를 검증하지 못한 요인입니다."),
			Param.number("wValue", "비중: 가치", 10, 0, 100, "", "PER·PBR의 비중입니다. 아직 효과를 검증하지 못한 요인입니다."),
			Param.number("flowDays", "수급 확인 기간", 5, 3, 20, "거래일", "최근 며칠간의 외국인·기관 순매수를 볼지 정합니다."),
			Param.select("requireFlow", "수급 필수", "OFF", onOff, "켜면 외국인+기관 합산 순매수가 양수인 종목만 후보가 됩니다. 모의투자에서는 수급 조회가 안 될 수 있어 끄는 편이 좋습니다."),
			Param.select("excludeLoss", "적자 기업 제외", "ON", onOff, "켜면 적자(EPS 음수) 종목은 점수와 상관없이 제외합니다."),
			Param.number("marketGuardPct", "시장 급락 시 매수 쉬기", 2.0, 0, 10, "%", "코스피가 하루에 이 비율 이상 내리면 그날은 신규 매수를 하지 않습니다. 0이면 끕니다."),
			Param.number("takeProfitPct", "목표 수익률(익절)", 5.0, 0.5, 50, "%", "보유 종목이 평균 매수가 대비 이만큼 오르면 매도합니다."),
			Param.number("stopLossPct", "손절선", 4.0, 1, 30, "%", "평균 매수가 대비 이만큼 내려가면 매도합니다."),
			Param.number("maxHoldDays", "최대 보유일", 10, 0, 60, "일", "이 기간이 지나도 목표에 못 미치면 장 마감 직전에 정리합니다. 0이면 제한 없음(권장하지 않음)."),
			Param.number("maxLossPct", "누적 손실 한도", 10, 1, 50, "%", "실현+평가 손실이 시드의 이 비율에 닿으면 전량 매도하고 자동매매를 멈춥니다."),
			Param.number("checkIntervalMinutes", "점검 주기", 10, Schedule.MIN_INTERVAL_MINUTES, 60, "분", "장중 몇 분마다 보유 종목을 확인할지 정합니다."));
	}

	@Override
	public Map<String, Object> normalize(Map<String, Object> in) {
		Map<String, Object> input = in == null ? Map.of() : in;
		Map<String, Object> c = new LinkedHashMap<>();
		c.put("market", "KRX");
		c.put("budget", bounded(input, "budget", 5_000_000, 100_000, 1e12, "시드"));
		c.put("maxPositions", (int)bounded(input, "maxPositions", 5, 1, 20, "최대 동시 보유 종목 수"));
		c.put("minScore", bounded(input, "minScore", 20, 0, 100, "매수 기준 점수"));
		c.put("candidates", (int)bounded(input, "candidates", 15, 5, 30, "분석할 후보 수"));
		double wt = bounded(input, "wTech", 60, 0, 100, "기술 비중");
		double wf = bounded(input, "wFlow", 30, 0, 100, "수급 비중");
		double wv = bounded(input, "wValue", 10, 0, 100, "가치 비중");
		if (wt + wf + wv <= 0) {
			throw invalid("세 요인의 비중 중 하나 이상은 0보다 커야 합니다.");
		}
		c.put("wTech", wt);
		c.put("wFlow", wf);
		c.put("wValue", wv);
		c.put("flowDays", (int)bounded(input, "flowDays", 5, 3, 20, "수급 확인 기간"));
		c.put("requireFlow", onOff(input, "requireFlow", "OFF"));
		c.put("excludeLoss", onOff(input, "excludeLoss", "ON"));
		c.put("marketGuardPct", bounded(input, "marketGuardPct", 2.0, 0, 10, "시장 급락 기준"));
		c.put("takeProfitPct", bounded(input, "takeProfitPct", 5.0, 0.5, 50, "목표 수익률"));
		c.put("stopLossPct", bounded(input, "stopLossPct", 4.0, 1, 30, "손절선"));
		c.put("maxHoldDays", (int)bounded(input, "maxHoldDays", 10, 0, 60, "최대 보유일"));
		c.put("maxLossPct", bounded(input, "maxLossPct", 10, 1, 50, "누적 손실 한도"));
		c.put("checkIntervalMinutes", (int)bounded(input, "checkIntervalMinutes", 10, Schedule.MIN_INTERVAL_MINUTES, 60, "점검 주기"));
		return c;
	}

	@Override
	public Schedule schedule(Map<String, Object> config) {
		return Schedule.marketInterval("KRX", ((Number)config.get("checkIntervalMinutes")).intValue(), 5, -10);
	}

	@Override
	public Limits limits(Map<String, Object> config) {
		BigDecimal budget = BigDecimal.valueOf(((Number)config.get("budget")).doubleValue()).setScale(0, RoundingMode.DOWN);
		int positions = ((Number)config.get("maxPositions")).intValue();
		return new Limits(budget, "KRW", 100.0 / positions, budget, ((Number)config.get("maxLossPct")).doubleValue());
	}

	@Override
	public String title(Map<String, Object> config) {
		return name() + " · 한국 · 시드 " + Currency.KRW.format(limits(config).budget());
	}

	// ─────────────────────────── 실행 ───────────────────────────

	@Override
	public void run(SkillContext ctx) {
		run(ctx, ZonedDateTime.now(Market.KRX.zone()));
	}

	/** 시각을 받는 실행(테스트에서 시계를 고정하려고 분리). */
	void run(SkillContext ctx, ZonedDateTime now) {
		Market group = Market.KRX;
		LocalDate today = now.toLocalDate();
		SkillContext.Services sv = ctx.services();
		AgentTradingService trading = sv.trading();

		if (!sv.calendar().isTradingDay(group, today) || !group.isOpenAt(now)) {
			if (ctx.manual()) {
				ctx.event("지금은 한국 장이 열려 있지 않아 아무것도 하지 않았습니다.");
			}
			return;
		}

		// 0) 계좌의 실제 보유와 장부 맞추기(하루 한 번)
		if (!today.toString().equals(ctx.stateStr("reconciledDate"))) {
			for (String line : trading.reconcile(ctx.task(), ctx.account())) {
				ctx.event(line);
			}
			ctx.state().put("reconciledDate", today.toString());
		}

		// 1) 보유 종목 점검 → 2) 누적 손실 한도
		SkillExits.manageOpenPositions(ctx, group, now, ctx.num("takeProfitPct", 5.0), ctx.num("stopLossPct", 4.0),
			(int)ctx.num("maxHoldDays", 10));
		if (SkillExits.enforceLossLimit(ctx)) {
			return;
		}

		// 3) 신규 매수: 하루 한 번
		if (today.toString().equals(ctx.stateStr("buyDate"))) {
			return;
		}
		LedgerView view = trading.view(ctx.task());
		int maxPositions = (int)ctx.num("maxPositions", 5);
		int slots = maxPositions - view.open().size();
		if (slots <= 0) {
			ctx.state().put("buyDate", today.toString());
			ctx.event("보유 종목이 최대(" + maxPositions + "개)라 오늘은 신규 매수를 하지 않았습니다.");
			return;
		}
		if (view.available().signum() <= 0) {
			ctx.state().put("buyDate", today.toString());
			ctx.event("남은 예산이 없어 오늘은 신규 매수를 하지 않았습니다.");
			return;
		}
		if (marketIsPanicking(ctx, today)) {
			return; // 오늘은 쉬되 buyDate 를 남기지 않아 점검 주기마다 다시 확인한다
		}

		List<Recommendation> pool;
		try {
			pool = sv.prediction().modelPicks(ctx.account(), "KRX", (int)ctx.num("candidates", 15));
		} catch (ApiException e) {
			ctx.event("후보 종목을 불러오지 못해 오늘은 매수하지 않았습니다: " + e.getMessage());
			ctx.state().put("buyDate", today.toString());
			return;
		}
		ctx.state().put("buyDate", today.toString());

		Set<String> held = new HashSet<>();
		view.open().forEach(p -> held.add(p.getSymbol().toUpperCase(Locale.ROOT)));
		pool = pool.stream().filter(r -> !held.contains(r.productNumber().toUpperCase(Locale.ROOT))).toList();
		if (pool.isEmpty()) {
			ctx.event("기술 요인 기준을 통과한 후보가 없어 오늘은 매수하지 않았습니다.");
			return;
		}

		Weights weights = new Weights(ctx.num("wTech", 60), ctx.num("wFlow", 30), ctx.num("wValue", 10));
		Filters filters = new Filters("ON".equals(ctx.str("requireFlow", "OFF")), !"OFF".equals(ctx.str("excludeLoss", "ON")));
		int flowDays = (int)ctx.num("flowDays", 5);
		double minScore = ctx.num("minScore", 20);

		List<Scored> scored = new ArrayList<>();
		int flowMissing = 0;
		for (Recommendation r : pool) {
			FlowSummary flow = null;
			Valuation val = null;
			try {
				flow = FactorDataService.summarize(factors.flows(ctx.account(), r.productNumber(), today), flowDays);
			} catch (RuntimeException e) {
				log.info("수급 조회 실패 {}: {}", r.productNumber(), e.getMessage());
			}
			try {
				val = factors.valuation(ctx.account(), r.productNumber());
			} catch (RuntimeException e) {
				log.info("밸류에이션 조회 실패 {}: {}", r.productNumber(), e.getMessage());
			}
			if (flow == null) {
				flowMissing++;
			}
			scored.add(new Scored(r, flow, val, MultiFactorScorer.score(new Input(r.productNumber(), r.name(), r.score(), flow, val),
				weights, filters)));
		}

		List<Scored> picks = scored.stream()
			.filter(s -> s.result().eligible() && s.result().composite() >= minScore)
			.sorted(Comparator.comparingDouble((Scored s) -> s.result().composite()).reversed())
			.limit(slots)
			.toList();
		record(today, scored, picks);

		if (flowMissing == scored.size()) {
			ctx.event("수급 정보를 가져오지 못했습니다(모의투자 서버는 지원하지 않을 수 있습니다). 수급 점수는 0으로 계산했습니다.");
		}
		if (picks.isEmpty()) {
			double best = scored.stream().mapToDouble(s -> s.result().composite()).max().orElse(0);
			long excluded = scored.stream().filter(s -> !s.result().eligible()).count();
			ctx.event(String.format(Locale.ROOT,
				"후보 %d개를 평가했지만 기준(종합 %.0f점 이상)을 만족하는 종목이 없어 오늘은 매수하지 않았습니다. (최고 %.0f점%s)",
				scored.size(), minScore, best, excluded > 0 ? ", 조건 제외 " + excluded + "개" : ""));
			return;
		}

		BigDecimal each = view.available().divide(BigDecimal.valueOf(picks.size()), 2, RoundingMode.DOWN)
			.min(view.limits().positionCap());
		ctx.event("종합 점수 상위 " + picks.size() + "개 종목에 종목당 최대 " + Currency.KRW.format(each) + "씩 매수합니다.");
		for (Scored s : picks) {
			Recommendation r = s.rec();
			try {
				Plan plan = sv.planner().plan(ctx.account(), r.productNumber(), r.market().name(), "BUY", null, each, null);
				String reason = String.format(Locale.ROOT, "종합 %.0f점 (기술 %+.0f · 수급 %+.0f · 가치 %+.0f)", s.result().composite(),
					s.result().tech() * 100, s.result().flow() * 100, s.result().value() * 100);
				OrderOutcome out = trading.buy(ctx.task(), ctx.account(), plan, reason);
				ctx.outcome(out);
				ctx.event(out.success()
					? String.format(Locale.ROOT, "%s %d주 매수 접수 · %s · %s", plan.name(), plan.quantity(), reason,
						String.join(" / ", s.result().notes()))
					: plan.name() + " 매수 실패: " + out.message());
			} catch (ApiException e) {
				ctx.event(r.name() + " 매수하지 못했습니다: " + e.getMessage());
			}
		}
	}

	private record Scored(Recommendation rec, FlowSummary flow, Valuation val, Result result) {
	}

	/** 코스피가 기준 이상 급락한 날이면 true(오늘 신규 매수를 쉰다). 조회 실패 시 실전 계좌는 안전하게 쉬고 모의 계좌는 진행한다. */
	private boolean marketIsPanicking(SkillContext ctx, LocalDate today) {
		double guard = ctx.num("marketGuardPct", 2.0);
		if (guard <= 0) {
			return false;
		}
		try {
			double change = factors.indexChangePct(ctx.account(), KOSPI);
			if (change <= -guard) {
				if (!today.toString().equals(ctx.stateStr("guardDate"))) {
					ctx.state().put("guardDate", today.toString());
					ctx.event(String.format(Locale.ROOT, "코스피가 오늘 %+.2f%%로 급락(기준 -%.1f%%)해 신규 매수를 쉽니다.", change, guard));
				}
				return true;
			}
			return false;
		} catch (RuntimeException e) {
			log.info("지수 조회 실패: {}", e.getMessage());
			if (ctx.account().isPaper()) {
				return false;
			}
			if (!today.toString().equals(ctx.stateStr("guardDate"))) {
				ctx.state().put("guardDate", today.toString());
				ctx.event("코스피 등락률을 확인하지 못해 안전을 위해 오늘 신규 매수를 쉽니다. (" + e.getMessage() + ")");
			}
			return true;
		}
	}

	/** 평가한 종목의 요인 값을 남긴다(이후 효과 검증용). 실패해도 매매에는 영향이 없다. */
	private void record(LocalDate today, List<Scored> scored, List<Scored> picks) {
		Set<String> picked = new HashSet<>();
		picks.forEach(p -> picked.add(p.rec().productNumber()));
		for (Scored s : scored) {
			try {
				if (snapshots.existsByTradeDateAndSymbol(today, s.rec().productNumber())) {
					continue;
				}
				Recommendation r = s.rec();
				snapshots.save(new FactorSnapshot(today, r.market(), r.productNumber(), r.name(), r.currentPrice(), r.score(),
					s.flow() == null ? null : s.flow().ratio(), s.flow() == null ? null : s.flow().foreignNet(),
					s.flow() == null ? null : s.flow().orgNet(), s.val() == null ? null : s.val().per(),
					s.val() == null ? null : s.val().pbr(), s.result().composite(), picked.contains(r.productNumber())));
			} catch (RuntimeException e) {
				log.warn("요인 기록 실패 {}: {}", s.rec().productNumber(), e.getMessage());
			}
		}
	}

	// ─────────────────────────── 입력 처리 ───────────────────────────

	private static String onOff(Map<String, Object> in, String key, String def) {
		Object v = in.get(key);
		String s = v == null || v.toString().isBlank() ? def : v.toString().trim().toUpperCase(Locale.ROOT);
		if (!s.equals("ON") && !s.equals("OFF")) {
			throw invalid(key + " 은(는) 켬(ON) 또는 끔(OFF) 이어야 합니다.");
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
