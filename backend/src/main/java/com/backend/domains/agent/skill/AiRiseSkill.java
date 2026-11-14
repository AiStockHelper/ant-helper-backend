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

import org.springframework.stereotype.Component;

import com.backend.common.exception.ApiException;
import com.backend.common.exception.ErrorCode;
import com.backend.domains.agent.AgentPosition;
import com.backend.domains.agent.AgentTradingService;
import com.backend.domains.agent.AgentTradingService.LedgerView;
import com.backend.domains.agent.Limits;
import com.backend.domains.agent.Schedule;
import com.backend.domains.trade.TradePlanner.Plan;
import com.backend.market.Currency;
import com.backend.market.Market;
import com.backend.market.dto.MarketDtos.OrderOutcome;
import com.backend.market.dto.MarketDtos.Recommendation;

import lombok.extern.slf4j.Slf4j;

/**
 * AI 상승 종목 자동매매.
 * 주간동아 「"AI매매프로그램으로 주식투자해 1년간 233% 수익 냈다"」(2023.6.16)의 방식을 옮긴 스킬이다:
 * 다음 거래일에 일정 비율 이상 오를 것으로 예측되는 종목을 사고, 목표 수익률에 도달하면 판다.
 * 기사와 달리 손절선·최대 보유일·누적 손실 한도를 코드로 강제한다.
 */
@Slf4j
@Component
public class AiRiseSkill implements Skill {

	public static final String ID = "ai-rise-trader";

	@Override
	public String id() {
		return ID;
	}

	@Override
	public String name() {
		return "AI 상승 종목 자동매매";
	}

	@Override
	public String summary() {
		return "AI가 오를 것 같은 종목을 골라 사고, 목표 수익률(예: +2%)에 도달하면 자동으로 팝니다.";
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
			AI 예측 모델이 유동성 좋은 종목들의 **다음 거래일 등락**을 예측합니다. 예상 상승률이 기준(기본 +1%)을 넘고 신뢰도가 충분한 종목을 \
			정해 둔 **시드 안에서 나눠 사고**, 보유 종목이 **목표 수익률(기본 +2%)에 도달하면 자동으로 매도**합니다. \
			목표에 못 미치면 며칠 더 들고 가지만, **손절선·최대 보유일·누적 손실 한도**에서는 코드가 강제로 정리합니다.

			주간동아 2023년 6월 기사 「AI매매프로그램으로 주식투자해 1년간 233% 수익 냈다」에서 소개된 방식(내일 2% 오를 종목을 사서 2% 오르면 판다)을 \
			참고했습니다.

			## 하루 흐름
			1. 장이 열리면 개장 5분 뒤부터 장중 N분마다(기본 10분) 깨어납니다. 휴장일은 건너뜁니다.
			2. 깨어날 때마다 보유 종목의 현재가를 확인해 **목표 수익 / 손절 / 보유일 초과**이면 매도합니다.
			3. 그날 첫 실행에서 전날 마감 기준 분석 결과 중 조건을 만족하는 종목을 시드 안에서 균등하게 매수합니다(하루 1회).
			4. 누적 손실이 한도에 닿으면 전량 정리하고 스스로 멈춥니다. 주문과 중요한 일은 이 스킬의 대화방에 기록되고, 실전 계좌는 이메일로도 알립니다.

			## 반드시 알아두세요
			- **수익을 보장하지 않습니다.** 기사의 성과는 인터뷰이 본인의 주장이며 검증되지 않았습니다.
			- 이 서비스의 예측은 일봉 기술지표와 가벼운 머신러닝을 합친 모델로, 기사 속 모델(코스피·코스닥 3천 종목, 600만 개 데이터 학습)보다 훨씬 단순합니다.
			- 2026년 4~10월 한국·미국 종목으로 직접 검증했을 때 이 모델의 다음 날 방향 적중률은 동전 던지기 수준(약 50%)이었습니다. 이 스킬의 '상승 예측'에 큰 기대를 걸지 말고, 모의 계좌와 작은 시드로 먼저 확인하세요.
			- 분석 대상은 매일 장 마감 후 **야간 배치가 분석해 둔 한국·미국 유동성 상위 종목**(각 최대 1,000개, 보통주만)입니다. 후보를 직접 지정하면 그 종목만 장중에 실시간으로 분석하며, 이 경우 모의 계좌는 12개·실전은 25개까지만 봅니다.
			- 수수료·거래세·슬리피지는 손익 계산에 포함하지 않습니다. 잦은 매매는 비용으로 손실이 날 수 있습니다.
			- 목표/손절은 체결가가 아니라 **N분마다 확인한 현재가** 기준이라, 급변동 때는 실제 체결가와 차이가 납니다.
			- 이 스킬이 산 종목만 관리하고 계좌의 다른 보유 종목은 건드리지 않습니다.
			- **모의 계좌에서 최소 몇 주 동안 결과를 확인**한 뒤 실전에 쓰세요.
			""";
	}

	@Override
	public List<Param> params() {
		return List.of(
			Param.select("market", "시장", "KRX", List.of(new Option("KRX", "한국 (원)"), new Option("US", "미국 (달러)")),
				"한국은 평일 09:00~15:30 KST, 미국은 뉴욕 09:30~16:00 기준입니다."),
			Param.number("budget", "시드(투자 한도)", 5_000_000, 100, 1_000_000_000_000.0, "원/달러",
				"이 스킬이 굴릴 수 있는 최대 금액입니다. 한국은 원, 미국은 달러 단위로 입력하세요. 이 금액을 넘겨 사지 않습니다."),
			Param.number("maxPositions", "최대 동시 보유 종목 수", 5, 1, 20, "종목", "시드를 이 수로 나눈 금액이 한 종목 한도가 됩니다."),
			Param.number("minPredictedRate", "매수 기준: 예상 상승률", 1.0, 0.1, 10, "%", "AI가 예측한 다음 거래일 상승률이 이 값 이상일 때만 매수합니다. 기사에서는 2%를 썼습니다."),
			Param.number("minConfidence", "매수 기준: 최소 신뢰도", 50, 20, 95, "점", "모델 신뢰도가 이 값 미만이면 제외합니다."),
			Param.number("takeProfitPct", "목표 수익률(익절)", 2.0, 0.5, 30, "%", "보유 종목이 평균 매수가 대비 이만큼 오르면 매도합니다."),
			Param.number("stopLossPct", "손절선", 5.0, 1, 30, "%", "평균 매수가 대비 이만큼 내려가면 매도합니다."),
			Param.number("maxHoldDays", "최대 보유일", 5, 0, 30, "일", "이 기간이 지나도 목표에 못 미치면 장 마감 직전에 정리합니다. 0이면 제한 없음(권장하지 않음)."),
			Param.number("maxLossPct", "누적 손실 한도", 10, 1, 50, "%", "실현+평가 손실이 시드의 이 비율에 닿으면 전량 매도하고 자동매매를 멈춥니다."),
			Param.number("checkIntervalMinutes", "점검 주기", 10, Schedule.MIN_INTERVAL_MINUTES, 60, "분", "장중 몇 분마다 보유 종목을 확인할지 정합니다."),
			Param.text("universe", "후보 종목 직접 지정 (선택)",
				"쉼표로 구분한 종목 코드/티커. 비워 두면 기본 대표 종목 후보군을 씁니다. 예: 005930,000660 또는 AAPL,NVDA"));
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
		c.put("budget", bounded(input, "budget", krx ? 5_000_000 : 3_000, krx ? 100_000 : 100, 1e12, "시드"));
		c.put("maxPositions", (int)bounded(input, "maxPositions", 5, 1, 20, "최대 동시 보유 종목 수"));
		c.put("minPredictedRate", bounded(input, "minPredictedRate", 1.0, 0.1, 10, "매수 기준 예상 상승률"));
		c.put("minConfidence", bounded(input, "minConfidence", 50, 20, 95, "최소 신뢰도"));
		c.put("takeProfitPct", bounded(input, "takeProfitPct", 2.0, 0.5, 30, "목표 수익률"));
		c.put("stopLossPct", bounded(input, "stopLossPct", 5.0, 1, 30, "손절선"));
		c.put("maxHoldDays", (int)bounded(input, "maxHoldDays", 5, 0, 30, "최대 보유일"));
		c.put("maxLossPct", bounded(input, "maxLossPct", 10, 1, 50, "누적 손실 한도"));
		c.put("checkIntervalMinutes", (int)bounded(input, "checkIntervalMinutes", 10, Schedule.MIN_INTERVAL_MINUTES, 60, "점검 주기"));
		String universe = String.valueOf(input.getOrDefault("universe", "")).trim();
		if (!universe.isEmpty()) {
			List<String> codes = parseUniverse(universe);
			if (codes.size() > 60) {
				throw invalid("후보 종목은 최대 60개까지 지정할 수 있습니다.");
			}
			c.put("universe", String.join(",", codes));
		}
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
		return name() + " · " + (krx ? "한국" : "미국") + " · 시드 " + Currency.valueOf(l.currency()).format(l.budget());
	}

	// ─────────────────────────── 실행 ───────────────────────────

	@Override
	public void run(SkillContext ctx) {
		Market group = "US".equals(ctx.str("market", "KRX")) ? Market.NASDAQ : Market.KRX;
		String groupName = group == Market.KRX ? "KRX" : "US";
		ZonedDateTime now = ZonedDateTime.now(group.zone());
		LocalDate today = now.toLocalDate();
		SkillContext.Services sv = ctx.services();
		AgentTradingService trading = sv.trading();

		if (!sv.calendar().isTradingDay(group, today) || !group.isOpenAt(now)) {
			if (ctx.manual()) {
				ctx.event("지금은 " + (group == Market.KRX ? "한국" : "미국") + " 장이 열려 있지 않아 아무것도 하지 않았습니다.");
			}
			return;
		}

		double target = ctx.num("takeProfitPct", 2.0);
		double stop = ctx.num("stopLossPct", 5.0);
		int maxHold = (int)ctx.num("maxHoldDays", 5);
		int maxPositions = (int)ctx.num("maxPositions", 5);
		var task = ctx.task();
		var account = ctx.account();

		// 0) 계좌의 실제 보유와 장부 맞추기(하루 한 번)
		if (!today.toString().equals(ctx.stateStr("reconciledDate"))) {
			for (String line : trading.reconcile(task, account)) {
				ctx.event(line);
			}
			ctx.state().put("reconciledDate", today.toString());
		}

		// 1) 보유 종목 점검: 익절 / 손절 / 보유일 초과
		long minutesToClose = Duration.between(now, now.with(group.closeTime())).toMinutes();
		LedgerView view = trading.view(task);
		for (AgentPosition p : view.open()) {
			if ((p.getMarket() == Market.KRX) != (group == Market.KRX)) {
				continue;
			}
			try {
				BigDecimal price = sv.marketData().quote(account, p.getMarket(), p.getSymbol()).price();
				trading.mark(p.getId(), price);
				p.mark(price);
				double pct = p.pnlPct();
				String reason = null;
				if (pct >= target) {
					reason = String.format(Locale.ROOT, "목표 수익 도달 (%+.2f%%)", pct);
				} else if (pct <= -stop) {
					reason = String.format(Locale.ROOT, "손절선 도달 (%+.2f%%)", pct);
				} else if (maxHold > 0 && p.heldDays(group.zone()) >= maxHold && minutesToClose <= 40) {
					reason = String.format(Locale.ROOT, "최대 보유일(%d일) 초과 정리 (%+.2f%%)", maxHold, pct);
				}
				if (reason != null) {
					sellPosition(ctx, p, reason);
				}
			} catch (ApiException e) {
				ctx.event(p.getName() + " 점검 중 문제: " + e.getMessage());
			}
		}

		// 2) 누적 손실 한도: 넘으면 전량 정리하고 멈춘다
		view = trading.view(task);
		if (view.limits() != null && view.totalPnl().add(view.limits().lossLimitAmount()).signum() <= 0) {
			ctx.event("누적 손실이 한도(" + Currency.valueOf(view.limits().currency()).format(view.limits().lossLimitAmount())
				+ ")에 도달했습니다. 보유 종목을 모두 정리하고 자동매매를 멈춥니다.");
			List<String> lines = new ArrayList<>();
			for (OrderOutcome o : trading.liquidate(task, account, lines)) {
				ctx.outcome(o);
			}
			lines.forEach(ctx::event);
			ctx.requestPause("누적 손실이 한도에 도달해 자동으로 멈췄습니다. 설정을 확인한 뒤 다시 시작해 주세요.");
			return;
		}

		// 3) 신규 매수: 하루 한 번
		if (today.toString().equals(ctx.stateStr("buyDate"))) {
			return;
		}
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

		double minRise = ctx.num("minPredictedRate", 1.0);
		double minConf = ctx.num("minConfidence", 50);
		List<String> universe = parseUniverse(ctx.str("universe", ""));
		List<Recommendation> ranked = sv.prediction().recommend(account, groupName, Math.max(slots * 3, 10), universe);
		Set<String> held = new HashSet<>();
		view.open().forEach(p -> held.add(p.getSymbol().toUpperCase(Locale.ROOT)));
		List<Recommendation> picks = ranked.stream()
			.filter(r -> "UP".equalsIgnoreCase(r.direction()) && r.predictedChangeRate() >= minRise && r.confidence() >= minConf)
			.filter(r -> !held.contains(r.productNumber().toUpperCase(Locale.ROOT)))
			.limit(slots)
			.toList();
		ctx.state().put("buyDate", today.toString());

		if (picks.isEmpty()) {
			double best = ranked.stream().mapToDouble(Recommendation::predictedChangeRate).max().orElse(0);
			ctx.event(String.format(Locale.ROOT,
				"후보 %d개를 분석했지만 기준(예상 +%.1f%% 이상, 신뢰도 %.0f 이상)을 만족하는 종목이 없어 오늘은 매수하지 않았습니다. (최고 예상 %+.2f%%)",
				ranked.size(), minRise, minConf, best));
			return;
		}

		BigDecimal each = view.available().divide(BigDecimal.valueOf(picks.size()), 2, RoundingMode.DOWN)
			.min(view.limits().positionCap());
		ctx.event("AI가 고른 " + picks.size() + "개 종목에 종목당 최대 "
			+ Currency.valueOf(view.limits().currency()).format(each) + "씩 매수합니다.");
		for (Recommendation r : picks) {
			try {
				Plan plan = sv.planner().plan(account, r.productNumber(), r.market().name(), "BUY", null, each, null);
				String reason = String.format(Locale.ROOT, "AI 예측 %+.2f%% (신뢰도 %.0f)", r.predictedChangeRate(), r.confidence());
				OrderOutcome out = trading.buy(task, account, plan, reason);
				ctx.outcome(out);
				ctx.event(out.success()
					? String.format(Locale.ROOT, "%s %d주 매수 접수 · %s", plan.name(), plan.quantity(), reason)
					: plan.name() + " 매수 실패: " + out.message());
			} catch (ApiException e) {
				ctx.event(r.name() + " 매수하지 못했습니다: " + e.getMessage());
			}
		}
	}

	private void sellPosition(SkillContext ctx, AgentPosition p, String reason) {
		Plan plan = ctx.services().planner().plan(ctx.account(), p.getSymbol(), p.getMarket().name(), "SELL", p.getQuantity(),
			null, null);
		OrderOutcome out = ctx.services().trading().sell(ctx.task(), ctx.account(), plan, reason);
		ctx.outcome(out);
		ctx.event(out.success()
			? String.format(Locale.ROOT, "%s %d주 매도 접수 · %s", p.getName(), p.getQuantity(), reason)
			: p.getName() + " 매도 실패: " + out.message() + " (" + reason + ")");
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

	private static List<String> parseUniverse(String text) {
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
