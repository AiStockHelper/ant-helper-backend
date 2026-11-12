package com.backend.domains.agent.skill;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.backend.common.exception.ApiException;
import com.backend.common.exception.ErrorCode;
import com.backend.domains.agent.AgentPosition;
import com.backend.domains.agent.AgentTradingService;
import com.backend.domains.agent.AgentTradingService.LedgerView;
import com.backend.domains.agent.Limits;
import com.backend.domains.agent.Schedule;
import com.backend.domains.agent.skill.TrendRules.Mode;
import com.backend.domains.trade.TradePlanner.Plan;
import com.backend.market.Currency;
import com.backend.market.Market;
import com.backend.market.MarketDataService.Period;
import com.backend.market.dto.MarketDtos.Candle;
import com.backend.market.dto.MarketDtos.OrderOutcome;
import com.backend.market.dto.MarketDtos.StockSummary;

import lombok.extern.slf4j.Slf4j;

/**
 * 지수·ETF 추세/변동성 타깃 자동매매.
 * ETF 한 종목을 골라, 하루 한 번(마감 15분 전) 추세와 변동성을 보고 보유 비중을 정한다.
 * 모델의 가격 '예측'을 쓰지 않고, 이미 일어난 가격 흐름(이동평균·변동성)만 본다. 규칙은 20년 데이터로 검증했다
 * (ai/prediction/tools/etf_skill_backtest.py). 시장을 이기는 전략이 아니라 큰 하락을 줄이는 전략이다.
 */
@Slf4j
@Component
public class IndexEtfSkill implements Skill {

	public static final String ID = "index-etf-trend";

	private record EtfInfo(String code, String label, String market) {
	}

	private static final List<EtfInfo> ETFS = List.of(
		new EtfInfo("SPY", "SPY · 미국 S&P500 (달러)", "US"),
		new EtfInfo("QQQ", "QQQ · 미국 나스닥100 (달러)", "US"),
		new EtfInfo("VOO", "VOO · 미국 S&P500 (달러)", "US"),
		new EtfInfo("VTI", "VTI · 미국 전체 주식 (달러)", "US"),
		new EtfInfo("069500", "KODEX 200 · 한국 (원)", "KRX"),
		new EtfInfo("229200", "KODEX 코스닥150 · 한국 (원)", "KRX"),
		new EtfInfo("360750", "TIGER 미국S&P500 · 한국 상장 (원)", "KRX"),
		new EtfInfo("133690", "TIGER 미국나스닥100 · 한국 상장 (원)", "KRX"));

	@Override
	public String id() {
		return ID;
	}

	@Override
	public String name() {
		return "지수·ETF 추세 자동매매";
	}

	@Override
	public String summary() {
		return "지수 ETF 한 종목을 추세와 변동성에 따라 비중을 조절해 들고, 하락 추세에서는 현금으로 피합니다. 수익보다 큰 하락을 줄이는 쪽입니다.";
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
			지수 ETF **한 종목**을 정해 두고, 하루에 한 번 **마감 15분 전**에 그 ETF의 가격 흐름을 보고 얼마나 들고 있을지 정합니다. \
			가격을 '예측'하지 않고 이미 일어난 흐름만 봅니다.
			- **추세**: 현재가가 최근 N일 이동평균(기본 150일) 위에 있으면 상승 추세, 아래면 하락 추세입니다. 하락 추세면 전량 매도하고 현금으로 둡니다.
			- **변동성 타깃**: 최근 20일 변동성이 목표(기본 연 15%)보다 크면 비중을 줄이고 작으면 100%까지 늘립니다. 요동칠수록 덜 들고 있는 방식입니다. 매월 첫 거래일에 다시 계산합니다.
			- **모드**: `추세+변동성`(기본), `추세만`, `변동성만` 중에서 고를 수 있습니다.
			- 목표 비중과 실제 보유가 **허용폭(기본 시드의 10%p)** 이상 차이날 때만 사고팝니다. 하락 추세로 바뀌어 전량 매도할 때와 처음 진입할 때는 허용폭과 상관없이 실행합니다.
			- 주문이 일시 오류로 실패해도 다음 날 목표와 실제를 다시 비교해 바로잡습니다.

			## 과거 데이터로 본 결과 (2007년 12월~2026년 11월, 거래비용 반영)
			| ETF | 모드 | 연 수익 | 최대 낙폭 | 샤프 | 그냥 보유 (연 수익 / 최대 낙폭 / 샤프) |
			|---|---|---|---|---|---|
			| SPY | 추세150+변동성15% | +8.2% | -16.6% | 0.67 | +11.1% / -53.9% / 0.56 |
			| QQQ | 추세150+변동성15% | +11.7% | -17.7% | 0.82 | +15.7% / -51.2% / 0.70 |
			| KODEX 200 | 추세150+변동성15% | +6.1% | -31.6% | 0.41 | +11.7% / -46.9% / 0.51 |
			| KODEX 코스닥150 (2015년 10월~) | 추세150+변동성15% | +5.9% | -26.7% | 0.37 | +1.2% / -57.2% / 0.13 |

			- **큰 하락을 줄이는 효과는 있었습니다.** 미국 ETF는 최대 낙폭이 -50%대에서 -17% 안팎으로 줄었고, 위험을 감안한 성과(샤프)도 조금 올랐습니다.
			- **수익이 늘지는 않습니다.** 오히려 상승장에서는 현금 비중이 있는 만큼 연 수익이 3~4%p 낮습니다. 시장을 이기는 전략이 아니라 하락을 덜 맞는 전략입니다.
			- **한국 ETF에서는 효과가 약했습니다.** KODEX 200은 2008~2016년 박스권에서 추세가 잘 안 맞아 위험 대비 성과가 좋아지지 않았고, 코스닥150은 20년간 보유해도 거의 제자리였습니다. 한국 ETF를 고르실 거라면 이 점을 감안하세요.
			- TIGER 미국S&P500·나스닥100(한국 상장)은 20년 검증 자료가 없습니다(상장 이력이 짧음). 미국 ETF의 결과를 참고용으로만 보세요.

			## 위험과 한계
			- 과거 결과는 미래를 보장하지 않습니다. 검증 기간의 대부분이 미국 강세장이었고, 'QQQ'는 지난 20년 가장 잘 오른 자산이라는 사후 선택 효과가 섞여 있습니다.
			- 추세 전략은 **횡보장에서 잦은 사고팔기**(이동평균을 오르내리는 경우)로 손해를 봅니다. 기본 설정에서 연 10~12회 정도 거래가 일어났습니다.
			- 하락장에서도 신호가 늦어 **처음 -10~15%는 맞고** 팔게 됩니다. 반등이 빠를 때는 늦게 다시 삽니다.
			- 팔아서 생긴 현금에는 이자가 붙지 않습니다(과거 검증은 단기채 수익을 가정했습니다). 수수료·세금(양도세, 한국 ETF 배당소득세 등)과 환율 변동도 반영되지 않았습니다.
			- 이 스킬이 산 종목만 관리하고, 계좌의 다른 보유 종목은 건드리지 않습니다. 시드·누적 손실 한도(기본 25%)·1회 주문 한도를 코드가 강제하고, 손실 한도에 닿으면 전량 매도 후 스스로 멈춥니다.
			- 미국 ETF 주문은 현재가 근처 지정가로 나가며 체결되지 않을 수 있습니다. 체결 여부는 다음 날 장부 확인에서 반영됩니다.
			- **모의 계좌에서 몇 주 동안 먼저 확인**한 뒤 실전에 쓰세요.
			""";
	}

	@Override
	public List<Param> params() {
		List<Option> options = ETFS.stream().map(e -> new Option(e.code(), e.label())).toList();
		return List.of(
			Param.select("etf", "ETF", "SPY", options,
				"한 종목만 고릅니다. 미국 ETF는 달러, 한국 상장 ETF는 원 단위 시드로 운용합니다. 과거 검증은 SPY·QQQ가 가장 잘 맞았습니다."),
			Param.number("budget", "시드(투자 한도)", 3_000, 100, 1_000_000_000_000.0, "원/달러",
				"이 스킬이 굴릴 수 있는 최대 금액입니다. 미국 ETF는 달러, 한국 ETF는 원 단위로 입력하세요. 한국 ETF를 고르면 500만 원 같은 값으로 바꿔 주세요."),
			Param.select("mode", "전략", "BOTH", List.of(new Option("BOTH", "추세 + 변동성"), new Option("TREND", "추세만"), new Option("VOL", "변동성만")),
				"추세+변동성이 과거 검증에서 가장 낙폭이 작았습니다. 추세만은 오르는 동안 100% 들고 있고, 변동성만은 하락 추세에서도 일부 보유합니다."),
			Param.number("trendDays", "추세 기준(이동평균 일수)", 150, 50, 250, "일", "현재가가 이 기간 평균 위에 있으면 상승 추세입니다. 짧을수록 빨리 반응하지만 잦은 매매가 늘어납니다."),
			Param.number("targetVolPct", "목표 변동성(연)", 15, 5, 30, "%", "최근 20일 변동성이 이 값보다 크면 비중을 줄입니다. 낮출수록 안정적이지만 수익도 줄어듭니다."),
			Param.number("bandPct", "비중 조절 허용폭", 10, 3, 30, "%p", "목표와 현재 비중이 이만큼 이상 차이날 때만 사고팝니다. 너무 작으면 잦은 매매로 비용이 늘어납니다."),
			Param.number("maxLossPct", "누적 손실 한도", 25, 5, 50, "%", "실현+평가 손실이 시드의 이 비율에 닿으면 전량 매도하고 스스로 멈춥니다."));
	}

	@Override
	public Map<String, Object> normalize(Map<String, Object> in) {
		Map<String, Object> input = in == null ? Map.of() : in;
		String code = String.valueOf(input.getOrDefault("etf", "SPY")).trim().toUpperCase(Locale.ROOT);
		EtfInfo etf = ETFS.stream().filter(e -> e.code().equals(code)).findFirst()
			.orElseThrow(() -> invalid("지원하지 않는 ETF입니다: " + code));
		boolean krx = etf.market().equals("KRX");
		Mode mode;
		try {
			mode = Mode.valueOf(String.valueOf(input.getOrDefault("mode", "BOTH")).trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw invalid("전략은 BOTH, TREND, VOL 중 하나여야 합니다.");
		}
		Map<String, Object> c = new LinkedHashMap<>();
		c.put("etf", etf.code());
		c.put("market", etf.market());
		c.put("budget", bounded(input, "budget", krx ? 5_000_000 : 3_000, krx ? 100_000 : 100, 1e12, "시드"));
		c.put("mode", mode.name());
		c.put("trendDays", (int)bounded(input, "trendDays", 150, 50, 250, "추세 기준"));
		c.put("targetVolPct", bounded(input, "targetVolPct", 15, 5, 30, "목표 변동성"));
		c.put("bandPct", bounded(input, "bandPct", 10, 3, 30, "비중 조절 허용폭"));
		c.put("maxLossPct", bounded(input, "maxLossPct", 25, 5, 50, "누적 손실 한도"));
		return c;
	}

	@Override
	public Schedule schedule(Map<String, Object> config) {
		return Schedule.marketEvent((String)config.get("market"), "CLOSE", -15);
	}

	@Override
	public Limits limits(Map<String, Object> config) {
		boolean krx = "KRX".equals(config.get("market"));
		BigDecimal budget = BigDecimal.valueOf(((Number)config.get("budget")).doubleValue()).setScale(krx ? 0 : 2, RoundingMode.DOWN);
		// ETF 한 종목에 전액을 넣을 수 있다(종목 비중 100%). 하루 매수 한도는 시드 전체.
		return new Limits(budget, krx ? "KRW" : "USD", 100.0, budget, ((Number)config.get("maxLossPct")).doubleValue());
	}

	@Override
	public String title(Map<String, Object> config) {
		Limits l = limits(config);
		String label = ETFS.stream().filter(e -> e.code().equals(config.get("etf"))).map(EtfInfo::code).findFirst().orElse("ETF");
		return name() + " · " + label + " · 시드 " + Currency.valueOf(l.currency()).format(l.budget());
	}

	// ─────────────────────────── 실행 ───────────────────────────

	@Override
	public void run(SkillContext ctx) {
		runAt(ctx, ZonedDateTime.now());
	}

	/** 테스트에서 시각을 정해 실행할 수 있도록 나눈 본체. */
	void runAt(SkillContext ctx, ZonedDateTime instant) {
		String etfCode = ctx.str("etf", "SPY");
		Market group = "US".equals(ctx.str("market", "KRX")) ? Market.NASDAQ : Market.KRX;
		Mode mode = Mode.valueOf(ctx.str("mode", "BOTH"));
		int trendDays = (int)ctx.num("trendDays", 150);
		double targetVol = ctx.num("targetVolPct", 15) / 100.0;
		double band = ctx.num("bandPct", 10) / 100.0;
		SkillContext.Services sv = ctx.services();
		AgentTradingService trading = sv.trading();
		var task = ctx.task();
		var account = ctx.account();

		ZonedDateTime now = instant.withZoneSameInstant(group.zone());
		LocalDate today = now.toLocalDate();
		if (!sv.calendar().isTradingDay(group, today) || !group.isOpenAt(now)) {
			if (ctx.manual()) {
				ctx.event("지금은 " + (group == Market.KRX ? "한국" : "미국") + " 장이 열려 있지 않아 아무것도 하지 않았습니다.");
			}
			return;
		}

		StockSummary stock = sv.marketData().resolve(etfCode, null);
		Currency cur = stock.currency();

		// 0) 계좌의 실제 보유와 장부 맞추기(하루 한 번)
		if (!today.toString().equals(ctx.stateStr("reconciledDate"))) {
			for (String line : trading.reconcile(task, account)) {
				ctx.event(line);
			}
			ctx.state().put("reconciledDate", today.toString());
		}

		// 1) 현재가와 장부 평가
		BigDecimal priceBd = sv.marketData().quote(account, stock.market(), stock.productNumber()).price();
		double price = priceBd.doubleValue();
		LedgerView view = trading.view(task);
		AgentPosition held = findHeld(view, stock.productNumber());
		if (held != null) {
			trading.mark(held.getId(), priceBd);
			held.mark(priceBd);
		}
		view = trading.view(task);
		held = findHeld(view, stock.productNumber());

		// 2) 누적 손실 한도: 넘으면 전량 정리하고 멈춘다
		if (view.limits() != null && view.totalPnl().add(view.limits().lossLimitAmount()).signum() <= 0) {
			ctx.event("누적 손실이 한도(" + cur.format(view.limits().lossLimitAmount()) + ")에 도달했습니다. 보유 종목을 모두 정리하고 자동매매를 멈춥니다.");
			List<String> lines = new ArrayList<>();
			for (OrderOutcome o : trading.liquidate(task, account, lines)) {
				ctx.outcome(o);
			}
			lines.forEach(ctx::event);
			ctx.requestPause("누적 손실이 한도에 도달해 자동으로 멈췄습니다. 설정을 확인한 뒤 다시 시작해 주세요.");
			return;
		}

		// 3) 가격 흐름: 어제까지의 일봉 + 지금 가격(오늘 봉이 있으면 대체)
		double[] closes = closesWithPrice(sv, account, stock, today, price, Math.max(trendDays, 60) + 30);
		boolean needTrend = TrendRules.usesTrend(mode);
		boolean needVol = TrendRules.usesVol(mode);
		double sma = needTrend ? TrendRules.sma(closes, trendDays) : Double.NaN;
		double vol = TrendRules.annualVol(closes, TrendRules.VOL_DAYS);
		if ((needTrend && Double.isNaN(sma)) || (needVol && Double.isNaN(vol))) {
			ctx.event("가격 이력이 부족해(" + closes.length + "일) 오늘은 판단하지 않았습니다.");
			return;
		}
		boolean trendOn = needTrend ? price > sma : true;

		// 변동성 비중: 매월 첫 실행(또는 새로 진입할 때)에 다시 계산한다
		long heldQty = held == null ? 0 : held.getQuantity();
		double volWeight = 1.0;
		boolean monthly = false;
		if (needVol) {
			String month = today.getYear() + "-" + String.format("%02d", today.getMonthValue());
			Object stored = ctx.state().get("volWeight");
			if (!month.equals(ctx.stateStr("volMonth")) || heldQty == 0 || !(stored instanceof Number)) {
				volWeight = TrendRules.volWeight(vol, targetVol);
				monthly = !month.equals(ctx.stateStr("volMonth"));
				ctx.state().put("volWeight", volWeight);
				ctx.state().put("volMonth", month);
			} else {
				volWeight = ((Number)stored).doubleValue();
			}
		}
		double weight = TrendRules.targetWeight(mode, trendOn, volWeight);
		double equity = view.limits().budget().add(view.totalPnl()).doubleValue();
		double currentWeight = equity > 0 ? heldQty * price / equity : 0;
		long delta = TrendRules.deltaShares(weight, equity, heldQty, price, band);

		String status = status(stock, cur, price, needTrend, trendDays, sma, trendOn, needVol, vol, weight, currentWeight);
		Boolean prevTrend = ctx.state().get("trendOn") instanceof Boolean b ? b : null;
		ctx.state().put("trendOn", trendOn);
		boolean flipped = needTrend && prevTrend != null && prevTrend != trendOn;

		if (delta == 0) {
			if (ctx.manual() || flipped || monthly) {
				ctx.event(status + " → 변동 없음.");
			}
			return;
		}

		// 4) 주문
		try {
			if (delta > 0) {
				long qty = delta;
				long affordable = (long)Math.floor(view.available().doubleValue() / price);
				if (qty > affordable) {
					qty = affordable;
				}
				if (qty < 1) {
					ctx.event(status + " → 매수해야 하지만 남은 예산이 부족해 하지 않았습니다.");
					return;
				}
				Plan plan = sv.planner().plan(account, stock.productNumber(), stock.market().name(), "BUY", qty, null, null);
				String reason = reason(trendOn, needTrend, weight, vol, needVol);
				OrderOutcome out = trading.buy(task, account, plan, reason);
				ctx.outcome(out);
				ctx.event(status + " → " + (out.success() ? String.format(Locale.ROOT, "%s %d주 매수 접수", plan.name(), plan.quantity())
					: plan.name() + " 매수 " + (out.pending() ? "대기열 등록" : "실패") + ": " + out.message()));
			} else {
				long qty = -delta;
				Plan plan = sv.planner().plan(account, stock.productNumber(), stock.market().name(), "SELL", qty, null, null);
				String reason = weight <= 0 ? (needTrend && !trendOn ? "하락 추세 전환(현재가가 " + trendDays + "일 평균 아래)" : "목표 비중 0%")
					: reason(trendOn, needTrend, weight, vol, needVol) + " (비중 축소)";
				OrderOutcome out = trading.sell(task, account, plan, reason);
				ctx.outcome(out);
				ctx.event(status + " → " + (out.success() ? String.format(Locale.ROOT, "%s %d주 매도 접수", plan.name(), plan.quantity())
					: plan.name() + " 매도 " + (out.pending() ? "대기열 등록" : "실패") + ": " + out.message()));
			}
		} catch (ApiException e) {
			ctx.event(status + " → 주문하지 못했습니다: " + e.getMessage());
		}
	}

	private static AgentPosition findHeld(LedgerView view, String symbol) {
		return view.open().stream().filter(p -> p.getSymbol().equalsIgnoreCase(symbol)).findFirst().orElse(null);
	}

	/** 어제까지의 종가(오래된 순)에 지금 가격을 이어 붙인다. 오늘 날짜의 봉이 이미 있으면 빼고 지금 가격으로 대신한다. */
	private static double[] closesWithPrice(SkillContext.Services sv, com.backend.domains.member.domain.MemberAccount account,
		StockSummary stock, LocalDate today, double price, int want) {
		List<Candle> candles = sv.marketData().candles(account, stock.market(), stock.productNumber(), Period.D, Math.min(500, want + 20));
		List<Double> list = new ArrayList<>();
		String todayIso = today.toString();
		for (Candle c : candles) {
			if (c.close() == null || c.close().signum() <= 0 || todayIso.equals(c.date())) {
				continue;
			}
			list.add(c.close().doubleValue());
		}
		list.add(price);
		double[] arr = new double[list.size()];
		for (int i = 0; i < arr.length; i++) {
			arr[i] = list.get(i);
		}
		return arr;
	}

	private static String status(StockSummary stock, Currency cur, double price, boolean needTrend, int trendDays, double sma,
		boolean trendOn, boolean needVol, double vol, double weight, double currentWeight) {
		StringBuilder sb = new StringBuilder();
		sb.append(stock.name()).append(" 현재가 ").append(cur.format(BigDecimal.valueOf(price)));
		if (needTrend) {
			sb.append(" · ").append(trendDays).append("일 평균 ").append(cur.format(BigDecimal.valueOf(sma)))
				.append(trendOn ? " 위(상승 추세)" : " 아래(하락 추세)");
		}
		if (needVol) {
			sb.append(String.format(Locale.ROOT, " · 20일 변동성 연 %.1f%%", vol * 100));
		}
		sb.append(String.format(Locale.ROOT, " → 목표 비중 %.0f%% (현재 %.0f%%)", weight * 100, currentWeight * 100));
		return sb.toString();
	}

	private static String reason(boolean trendOn, boolean needTrend, double weight, double vol, boolean needVol) {
		String r = needTrend ? (trendOn ? "상승 추세" : "하락 추세") : "추세 미사용";
		if (needVol) {
			r += String.format(Locale.ROOT, ", 변동성 연 %.1f%%", vol * 100);
		}
		return r + String.format(Locale.ROOT, " → 목표 비중 %.0f%%", weight * 100);
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
