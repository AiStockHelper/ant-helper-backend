package com.backend.domains.agent.skill;

/**
 * 지수·ETF 추세/변동성 타깃 스킬의 계산 규칙(순수 함수). 규칙은 ai/prediction/tools/etf_skill_backtest.py 와 같아야 한다.
 * <ul>
 *   <li>TREND: 현재가가 이동평균 위면 목표 비중 100%, 아니면 0%</li>
 *   <li>VOL: 목표 비중 = min(1, 목표 변동성 / 최근 20일 변동성)</li>
 *   <li>BOTH: 추세가 꺼져 있으면 0%, 켜져 있으면 VOL 비중</li>
 * </ul>
 */
final class TrendRules {

	enum Mode {TREND, VOL, BOTH}

	static final int VOL_DAYS = 20;
	private static final double TRADING_DAYS = 252.0;

	private TrendRules() {
	}

	static boolean usesTrend(Mode m) {
		return m == Mode.TREND || m == Mode.BOTH;
	}

	static boolean usesVol(Mode m) {
		return m == Mode.VOL || m == Mode.BOTH;
	}

	/** 마지막 window 개 종가의 단순 평균. 데이터가 모자라면 NaN. */
	static double sma(double[] closes, int window) {
		if (window <= 0 || closes.length < window) {
			return Double.NaN;
		}
		double sum = 0;
		for (int i = closes.length - window; i < closes.length; i++) {
			sum += closes[i];
		}
		return sum / window;
	}

	/** 마지막 days 개 일간 수익률의 표본표준편차를 연율화한 값(예: 0.18 = 연 18%). 데이터가 모자라면 NaN. */
	static double annualVol(double[] closes, int days) {
		if (days < 2 || closes.length < days + 1) {
			return Double.NaN;
		}
		double[] r = new double[days];
		for (int i = 0; i < days; i++) {
			int k = closes.length - days + i;
			r[i] = closes[k] / closes[k - 1] - 1.0;
		}
		double mean = 0;
		for (double v : r) {
			mean += v;
		}
		mean /= days;
		double ss = 0;
		for (double v : r) {
			ss += (v - mean) * (v - mean);
		}
		return Math.sqrt(ss / (days - 1)) * Math.sqrt(TRADING_DAYS);
	}

	/** 변동성 타깃 비중(0~1). 변동성을 모르면 0. */
	static double volWeight(double vol, double targetVol) {
		if (Double.isNaN(vol) || vol <= 0) {
			return 0.0;
		}
		return Math.min(1.0, targetVol / vol);
	}

	/** 모드에 따른 목표 비중. volWeight 는 TREND 모드에서는 쓰이지 않는다. */
	static double targetWeight(Mode mode, boolean trendOn, double volWeight) {
		return switch (mode) {
			case TREND -> trendOn ? 1.0 : 0.0;
			case VOL -> volWeight;
			case BOTH -> trendOn ? volWeight : 0.0;
		};
	}

	/**
	 * 현재 보유(heldQty)를 목표 비중에 맞추려면 몇 주를 더 사고(+) 팔아야(-) 하는가.
	 * 전량 매도(목표 0%)와 신규 진입(보유 0)은 허용폭과 상관없이 실행하고, 그 외에는 비중 차이가 허용폭(band, 시드 대비 비율)
	 * 이하이면 거래하지 않는다(잦은 매매와 비용 방지).
	 */
	static long deltaShares(double weight, double equity, long heldQty, double price, double band) {
		if (price <= 0 || equity <= 0) {
			return 0;
		}
		if (weight <= 0) {
			return -heldQty;
		}
		double target = weight * equity;
		if (heldQty <= 0) {
			return (long)Math.floor(target / price);
		}
		double diff = target - heldQty * price;
		if (Math.abs(diff) / equity <= band) {
			return 0;
		}
		long shares = (long)Math.floor(Math.abs(diff) / price);
		return diff > 0 ? shares : -Math.min(heldQty, shares);
	}
}
