package com.backend.domains.agent.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

import com.backend.domains.agent.skill.TrendRules.Mode;

class TrendRulesTest {

	@Test
	void smaUsesTheLastWindowOnly() {
		double[] closes = {1, 2, 3, 4, 5, 6};
		assertThat(TrendRules.sma(closes, 3)).isEqualTo(5.0);
		assertThat(TrendRules.sma(closes, 6)).isEqualTo(3.5);
		assertThat(TrendRules.sma(closes, 7)).isNaN();
	}

	@Test
	void annualVolMatchesTheSampleStdOfDailyReturns() {
		// 일간 수익률이 +1%, -1% 를 번갈아: 표본표준편차 = sqrt(20 * 0.01^2 / 19)
		double[] closes = new double[21];
		closes[0] = 100;
		for (int i = 1; i < closes.length; i++) {
			closes[i] = closes[i - 1] * (i % 2 == 1 ? 1.01 : 0.99);
		}
		double daily = Math.sqrt(20 * 0.0001 / 19);
		// 가격이 오르내리며 수익률이 정확히 ±1% 가 되지는 않으므로 근사로 비교한다
		assertThat(TrendRules.annualVol(closes, 20)).isCloseTo(daily * Math.sqrt(252), within(0.01));
		assertThat(TrendRules.annualVol(closes, 21)).isNaN();
	}

	@Test
	void volWeightIsCappedAtOneAndZeroWithoutData() {
		assertThat(TrendRules.volWeight(0.30, 0.15)).isEqualTo(0.5);
		assertThat(TrendRules.volWeight(0.10, 0.15)).isEqualTo(1.0);
		assertThat(TrendRules.volWeight(Double.NaN, 0.15)).isZero();
		assertThat(TrendRules.volWeight(0.0, 0.15)).isZero();
	}

	@Test
	void targetWeightByMode() {
		assertThat(TrendRules.targetWeight(Mode.TREND, true, 0.4)).isEqualTo(1.0);
		assertThat(TrendRules.targetWeight(Mode.TREND, false, 0.4)).isZero();
		assertThat(TrendRules.targetWeight(Mode.VOL, false, 0.4)).isEqualTo(0.4);
		assertThat(TrendRules.targetWeight(Mode.BOTH, true, 0.4)).isEqualTo(0.4);
		assertThat(TrendRules.targetWeight(Mode.BOTH, false, 0.4)).isZero();
	}

	@Test
	void enteringAndExitingIgnoreTheBand() {
		// 보유 0 이면 목표 금액만큼 산다(버림)
		assertThat(TrendRules.deltaShares(0.5, 1000, 0, 30, 0.10)).isEqualTo(16);
		// 목표 0% 면 있는 만큼 전부 판다
		assertThat(TrendRules.deltaShares(0.0, 1000, 7, 30, 0.10)).isEqualTo(-7);
		assertThat(TrendRules.deltaShares(0.0, 1000, 0, 30, 0.10)).isZero();
	}

	@Test
	void rebalanceOnlyWhenTheGapExceedsTheBand() {
		// 시드 1000, 가격 10: 목표 50% = 500원 = 50주. 현재 45주(450원): 차이 5%p <= 10%p 라서 거래 없음
		assertThat(TrendRules.deltaShares(0.5, 1000, 45, 10, 0.10)).isZero();
		// 현재 30주(300원): 차이 20%p > 10%p -> 20주 매수
		assertThat(TrendRules.deltaShares(0.5, 1000, 30, 10, 0.10)).isEqualTo(20);
		// 현재 80주(800원): 차이 30%p -> 30주 매도
		assertThat(TrendRules.deltaShares(0.5, 1000, 80, 10, 0.10)).isEqualTo(-30);
	}

	@Test
	void neverSellsMoreThanHeldAndIgnoresBadInput() {
		// 목표 1% = 10원, 보유 50원어치(5주): 차이 40원 -> 4주 매도(보유 수량을 넘지 않음)
		assertThat(TrendRules.deltaShares(0.01, 1000, 5, 10, 0.01)).isEqualTo(-4);
		assertThat(TrendRules.deltaShares(0.5, 0, 5, 10, 0.10)).isZero();
		assertThat(TrendRules.deltaShares(0.5, 1000, 5, 0, 0.10)).isZero();
	}
}
