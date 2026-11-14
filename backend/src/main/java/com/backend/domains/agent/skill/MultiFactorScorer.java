package com.backend.domains.agent.skill;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.backend.market.FactorDataService.FlowSummary;
import com.backend.market.FactorDataService.Valuation;

/**
 * 멀티팩터 종합 점수. 기술(가격·거래량) · 수급(외국인·기관) · 가치(PER/PBR) 세 요인을 각각 -1~1 로 만든 뒤
 * 가중 평균해서 -100~100 점으로 돌려준다. 주문과 무관한 순수 계산이라 단독으로 테스트할 수 있다.
 *
 * <p>주의: 기술 요인만 과거 데이터로 검증된 순위 점수(야간 스크리닝)를 쓴다. 수급·가치 요인은 과거 데이터를 구하지 못해
 * 아직 백테스트하지 못했고, 아래 기준값은 일반적인 통념에 가까운 보수적인 값이다.
 */
public final class MultiFactorScorer {

	/** 스크리닝 요인 점수(-100~100) 중 이 값이면 기술 요인 만점으로 본다. */
	public static final double TECH_FULL_SCORE = 50.0;
	/** (외국인+기관 순매수)/거래대금 이 이 값이면 수급 요인(비율 부분) 만점으로 본다. */
	public static final double FLOW_FULL_RATIO = 0.05;

	private MultiFactorScorer() {
	}

	public record Weights(double tech, double flow, double value) {
		public Weights {
			if (tech < 0 || flow < 0 || value < 0 || tech + flow + value <= 0) {
				throw new IllegalArgumentException("가중치는 0 이상이고 합이 0보다 커야 합니다.");
			}
		}

		Weights normalized() {
			double sum = tech + flow + value;
			return new Weights(tech / sum, flow / sum, value / sum);
		}
	}

	/** 점수와 별개로 후보에서 아예 빼는 조건 */
	public record Filters(boolean requireInstitutionalBuying, boolean excludeLoss) {
	}

	public record Input(String symbol, String name, double techScore, FlowSummary flow, Valuation valuation) {
	}

	public record Result(
		String symbol, String name, double composite, double tech, double flow, double value,
		boolean eligible, String excludedReason, List<String> notes
	) {
	}

	public static Result score(Input in, Weights weights, Filters filters) {
		Weights w = weights.normalized();
		List<String> notes = new ArrayList<>();

		double tech = clamp(in.techScore() / TECH_FULL_SCORE);
		notes.add(String.format(Locale.ROOT, "기술 %+.0f점", in.techScore()));

		double flow = flowPart(in.flow(), notes);
		double value = valuePart(in.valuation(), notes);

		String excluded = null;
		if (filters.requireInstitutionalBuying() && (in.flow() == null || in.flow().combinedNet() <= 0)) {
			excluded = in.flow() == null ? "수급 데이터 없음(수급 필수 설정)" : "외국인·기관 순매수가 아님(수급 필수 설정)";
		}
		if (excluded == null && filters.excludeLoss() && in.valuation() != null && in.valuation().loss()) {
			excluded = "적자 기업 제외 설정";
		}

		double composite = (tech * w.tech() + flow * w.flow() + value * w.value()) * 100.0;
		return new Result(in.symbol(), in.name(), composite, tech, flow, value, excluded == null, excluded, notes);
	}

	/** 수급: 비율이 클수록, 외국인·기관이 같이 사고 있을수록 높다. 데이터가 없으면 0(중립). */
	static double flowPart(FlowSummary f, List<String> notes) {
		if (f == null) {
			notes.add("수급 정보 없음");
			return 0;
		}
		double base = clamp(f.ratio() / FLOW_FULL_RATIO) * 0.8;
		double bonus = f.foreignNet() > 0 && f.orgNet() > 0 ? 0.2 : f.foreignNet() < 0 && f.orgNet() < 0 ? -0.2 : 0;
		notes.add(String.format(Locale.ROOT, "수급 %d일 외국인·기관 순매수 %+.1f%%%s", f.days(), f.ratio() * 100,
			bonus > 0 ? " (동반 매수)" : bonus < 0 ? " (동반 매도)" : ""));
		return clamp(base + bonus);
	}

	/**
	 * 가치: PER 낮을수록 높다(PER 10 이하 만점 근처, 30 이상이면 감점). PBR 이 있으면 평균을 낸다.
	 * 업종 평균과 비교하지 않으므로 업종 특성(성장주·금융주 등)은 반영하지 못한다. 적자(PER 음수/EPS 음수)는 -0.5.
	 */
	static double valuePart(Valuation v, List<String> notes) {
		if (v == null || (v.per() == null && v.pbr() == null && v.eps() == null)) {
			notes.add("밸류에이션 정보 없음");
			return 0;
		}
		if (v.loss()) {
			notes.add("적자(PER 의미 없음)");
			return -0.5;
		}
		List<Double> parts = new ArrayList<>();
		if (v.per() != null && v.per() > 0) {
			parts.add(perScore(v.per()));
		}
		if (v.pbr() != null && v.pbr() > 0) {
			parts.add(pbrScore(v.pbr()));
		}
		if (parts.isEmpty()) {
			notes.add("밸류에이션 정보 없음");
			return 0;
		}
		notes.add(String.format(Locale.ROOT, "가치 PER %s · PBR %s", fmt(v.per()), fmt(v.pbr())));
		return clamp(parts.stream().mapToDouble(Double::doubleValue).average().orElse(0));
	}

	static double perScore(double per) {
		if (per <= 8) {
			return 1.0;
		}
		if (per <= 15) {
			return 1.0 - (per - 8) / 7 * 0.8; // 8 → 1.0, 15 → 0.2
		}
		if (per <= 25) {
			return 0.2 - (per - 15) / 10 * 0.5; // 15 → 0.2, 25 → -0.3
		}
		if (per <= 50) {
			return -0.3 - (per - 25) / 25 * 0.5; // 25 → -0.3, 50 → -0.8
		}
		return -1.0;
	}

	static double pbrScore(double pbr) {
		if (pbr <= 0.8) {
			return 1.0;
		}
		if (pbr <= 1.5) {
			return 1.0 - (pbr - 0.8) / 0.7 * 0.8; // 0.8 → 1.0, 1.5 → 0.2
		}
		if (pbr <= 3) {
			return 0.2 - (pbr - 1.5) / 1.5 * 0.6; // 1.5 → 0.2, 3 → -0.4
		}
		return pbr <= 6 ? -0.4 - (pbr - 3) / 3 * 0.4 : -1.0;
	}

	private static String fmt(Double v) {
		return v == null ? "-" : String.format(Locale.ROOT, "%.1f", v);
	}

	private static double clamp(double v) {
		return Math.max(-1.0, Math.min(1.0, v));
	}
}
