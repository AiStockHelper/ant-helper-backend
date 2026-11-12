package com.backend.domains.agent.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.backend.domains.agent.skill.MultiFactorScorer.Filters;
import com.backend.domains.agent.skill.MultiFactorScorer.Input;
import com.backend.domains.agent.skill.MultiFactorScorer.Result;
import com.backend.domains.agent.skill.MultiFactorScorer.Weights;
import com.backend.market.FactorDataService;
import com.backend.market.FactorDataService.FlowDay;
import com.backend.market.FactorDataService.FlowSummary;
import com.backend.market.FactorDataService.Valuation;

class MultiFactorScorerTest {

	private static final Weights DEFAULT = new Weights(60, 30, 10);
	private static final Filters NONE = new Filters(false, false);

	private static FlowSummary flow(double ratio, double foreign, double org) {
		return new FlowSummary(5, foreign, org, ratio, 0, 0);
	}

	@Test
	void strongOnAllThreeFactorsScoresHigh() {
		Result r = MultiFactorScorer.score(new Input("A", "A", 40, flow(0.04, 100, 80), new Valuation(8.0, 0.8, 1000.0, 10000.0)),
			DEFAULT, NONE);
		assertThat(r.composite()).isBetween(80.0, 86.0);
		assertThat(r.tech()).isEqualTo(0.8, org.assertj.core.data.Offset.offset(1e-9));
		assertThat(r.flow()).isGreaterThan(0.8);
		assertThat(r.value()).isEqualTo(1.0);
		assertThat(r.eligible()).isTrue();
	}

	@Test
	void sellingAndExpensiveScoresLow() {
		Result r = MultiFactorScorer.score(new Input("B", "B", 12, flow(-0.03, -50, -40), new Valuation(60.0, 4.0, 100.0, 1000.0)),
			DEFAULT, NONE);
		assertThat(r.composite()).isLessThan(0);
		assertThat(r.flow()).isLessThan(0);
		assertThat(r.value()).isLessThan(0);
	}

	@Test
	void weightsAreRatiosNotAbsoluteSizes() {
		Input in = new Input("A", "A", 40, flow(0.04, 100, 80), new Valuation(8.0, 0.8, 1000.0, 10000.0));
		double a = MultiFactorScorer.score(in, new Weights(60, 30, 10), NONE).composite();
		double b = MultiFactorScorer.score(in, new Weights(6, 3, 1), NONE).composite();
		assertThat(a).isEqualTo(b, org.assertj.core.data.Offset.offset(1e-9));
	}

	@Test
	void zeroWeightIgnoresThatFactor() {
		Input in = new Input("A", "A", 25, flow(-0.05, -10, -10), new Valuation(60.0, 5.0, 10.0, 100.0));
		Result onlyTech = MultiFactorScorer.score(in, new Weights(1, 0, 0), NONE);
		assertThat(onlyTech.composite()).isEqualTo(50.0, org.assertj.core.data.Offset.offset(1e-9));
	}

	@Test
	void missingFlowAndValuationAreNeutralNotPenalised() {
		Result r = MultiFactorScorer.score(new Input("C", "C", 30, null, null), DEFAULT, NONE);
		assertThat(r.flow()).isZero();
		assertThat(r.value()).isZero();
		assertThat(r.composite()).isEqualTo(0.6 * 0.6 * 100, org.assertj.core.data.Offset.offset(1e-9));
		assertThat(r.notes()).anyMatch(n -> n.contains("수급 정보 없음")).anyMatch(n -> n.contains("밸류에이션 정보 없음"));
	}

	@Test
	void lossMakingIsExcludedOnlyWhenFilterIsOn() {
		Input in = new Input("L", "L", 45, flow(0.04, 100, 80), new Valuation(0.0, 1.2, -300.0, 5000.0));
		assertThat(MultiFactorScorer.score(in, DEFAULT, new Filters(false, true)).eligible()).isFalse();
		assertThat(MultiFactorScorer.score(in, DEFAULT, new Filters(false, true)).excludedReason()).contains("적자");
		Result penalisedOnly = MultiFactorScorer.score(in, DEFAULT, new Filters(false, false));
		assertThat(penalisedOnly.eligible()).isTrue();
		assertThat(penalisedOnly.value()).isEqualTo(-0.5);
	}

	@Test
	void requireInstitutionalBuyingExcludesSellersAndMissingData() {
		Filters need = new Filters(true, false);
		assertThat(MultiFactorScorer.score(new Input("S", "S", 40, flow(-0.02, -30, 10), null), DEFAULT, need).eligible()).isFalse();
		assertThat(MultiFactorScorer.score(new Input("N", "N", 40, null, null), DEFAULT, need).eligible()).isFalse();
		assertThat(MultiFactorScorer.score(new Input("B", "B", 40, flow(0.01, 5, 5), null), DEFAULT, need).eligible()).isTrue();
	}

	@Test
	void flowScoreIsCappedAndBothBuyingAddsBonus() {
		List<String> notes = new ArrayList<>();
		assertThat(MultiFactorScorer.flowPart(flow(0.5, 10, 10), notes)).isEqualTo(1.0);
		assertThat(MultiFactorScorer.flowPart(flow(-0.5, -10, -10), notes)).isEqualTo(-1.0);
		assertThat(MultiFactorScorer.flowPart(flow(0.0, 10, -10), notes)).isZero();
	}

	@Test
	void valuationCurvesAreMonotonic() {
		double prev = Double.MAX_VALUE;
		for (double per : new double[] {5, 8, 12, 15, 20, 25, 40, 50, 80}) {
			double s = MultiFactorScorer.perScore(per);
			assertThat(s).isLessThanOrEqualTo(prev);
			prev = s;
		}
		prev = Double.MAX_VALUE;
		for (double pbr : new double[] {0.3, 0.8, 1.0, 1.5, 2.0, 3.0, 5.0, 9.0}) {
			double s = MultiFactorScorer.pbrScore(pbr);
			assertThat(s).isLessThanOrEqualTo(prev);
			prev = s;
		}
		assertThat(MultiFactorScorer.perScore(8)).isEqualTo(1.0);
		assertThat(MultiFactorScorer.perScore(500)).isEqualTo(-1.0);
	}

	@Test
	void invalidWeightsAreRejected() {
		assertThatThrownBy(() -> new Weights(0, 0, 0)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Weights(-1, 5, 5)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void flowSummaryUsesOnlyRequestedDaysAndCountsStreaks() {
		// 최근 날짜가 앞에 오는 순서
		List<FlowDay> days = List.of(
			new FlowDay(java.time.LocalDate.of(2026, 11, 5), 50, 30, -80, 1000),
			new FlowDay(java.time.LocalDate.of(2026, 11, 4), 20, -10, -10, 1000),
			new FlowDay(java.time.LocalDate.of(2026, 11, 3), 10, 10, -20, 1000),
			new FlowDay(java.time.LocalDate.of(2026, 11, 2), -500, -500, 1000, 1000));
		FlowSummary s = FactorDataService.summarize(days, 3);
		assertThat(s.days()).isEqualTo(3);
		assertThat(s.foreignNet()).isEqualTo(80);
		assertThat(s.orgNet()).isEqualTo(30);
		assertThat(s.ratio()).isEqualTo(110.0 / 3000.0, org.assertj.core.data.Offset.offset(1e-9));
		assertThat(s.foreignStreak()).isEqualTo(3);
		assertThat(s.orgStreak()).isEqualTo(1); // 11/4 에 순매도라 끊긴다
		assertThat(FactorDataService.summarize(List.of(), 5)).isNull();
		assertThat(FactorDataService.summarize(null, 5)).isNull();
	}
}
