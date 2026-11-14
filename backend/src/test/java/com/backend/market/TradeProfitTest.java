package com.backend.market;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.backend.market.TradeProfit.Fill;
import com.backend.market.TradeProfit.Realized;

class TradeProfitTest {

	private static final Instant T0 = Instant.parse("2026-11-10T00:00:00Z");

	private static Fill buy(int i, String key, long qty, double price, int minutes) {
		return new Fill(i, key, false, qty, BigDecimal.valueOf(price), T0.plusSeconds(minutes * 60L));
	}

	private static Fill sell(int i, String key, long qty, double price, int minutes) {
		return new Fill(i, key, true, qty, BigDecimal.valueOf(price), T0.plusSeconds(minutes * 60L));
	}

	@Test
	void sellProfitIsMeasuredAgainstTheAverageBuyPriceAndRateIsPerShare() {
		Map<Integer, Realized> r = TradeProfit.realized(List.of(buy(0, "A", 10, 100, 0), sell(1, "A", 4, 110, 5)));
		assertThat(r).containsOnlyKeys(1);
		assertThat(r.get(1).costPrice()).isEqualByComparingTo("100");
		assertThat(r.get(1).pnl()).isEqualByComparingTo("40");               // (110-100) × 4
		assertThat(r.get(1).pnlRate()).isEqualTo(10.0);
	}

	@Test
	void buysAtDifferentPricesAverageOutAndALaterSellUsesTheRunningAverage() {
		Map<Integer, Realized> r = TradeProfit.realized(List.of(
			buy(0, "A", 10, 100, 0), buy(1, "A", 10, 120, 1),               // 평균 110
			sell(2, "A", 5, 99, 2),                                           // 110 → 99: -11 × 5
			buy(3, "A", 5, 130, 3),                                           // 남은 15주(평균 110) + 5주(130) → 평균 115
			sell(4, "A", 20, 115.5, 4)));
		assertThat(r.get(2).pnl()).isEqualByComparingTo("-55");
		assertThat(r.get(2).pnlRate()).isEqualTo(-10.0);
		assertThat(r.get(4).costPrice()).isEqualByComparingTo("115");
		assertThat(r.get(4).pnl()).isEqualByComparingTo("10");               // (115.5-115) × 20
		assertThat(r.get(4).pnlRate()).isEqualTo(0.43);
	}

	@Test
	void stocksAreTrackedSeparatelyAndInputOrderDoesNotMatter() {
		List<Fill> fills = List.of(
			sell(0, "A", 5, 120, 10), sell(1, "B", 5, 50, 11),                // 일부러 매도를 먼저 적었다
			buy(2, "B", 5, 40, 1), buy(3, "A", 5, 100, 0));
		Map<Integer, Realized> r = TradeProfit.realized(fills);
		assertThat(r.get(0).pnl()).isEqualByComparingTo("100");
		assertThat(r.get(1).pnl()).isEqualByComparingTo("50");
		assertThat(r.get(1).pnlRate()).isEqualTo(25.0);
	}

	@Test
	void sellingMoreThanWasBoughtInTheWindowHasNoKnownCostAndDoesNotCorruptLaterTrades() {
		Map<Integer, Realized> r = TradeProfit.realized(List.of(
			buy(0, "A", 3, 100, 0),
			sell(1, "A", 5, 110, 1),                                          // 3주만 알고 5주를 팔았다 → 원가를 알 수 없다
			buy(2, "A", 2, 200, 2),
			sell(3, "A", 2, 210, 3)));                                        // 새로 산 2주만 기준으로 계산돼야 한다
		assertThat(r).containsOnlyKeys(3);
		assertThat(r.get(3).costPrice()).isEqualByComparingTo("200");
		assertThat(r.get(3).pnl()).isEqualByComparingTo("20");
	}

	@Test
	void aSellWithNoBuyAtAllAndInvalidFillsAreIgnored() {
		Map<Integer, Realized> r = TradeProfit.realized(List.of(
			sell(0, "A", 5, 110, 0), buy(1, "B", 0, 100, 0), buy(2, "B", 5, 0, 0), sell(3, "B", 5, 90, 1)));
		assertThat(r).isEmpty();
	}

	@Test
	void sellingEverythingResetsTheAverageSoTheNextRoundStartsFresh() {
		Map<Integer, Realized> r = TradeProfit.realized(List.of(
			buy(0, "A", 5, 100, 0), sell(1, "A", 5, 90, 1), buy(2, "A", 5, 50, 2), sell(3, "A", 5, 55, 3)));
		assertThat(r.get(1).pnl()).isEqualByComparingTo("-50");
		assertThat(r.get(3).costPrice()).isEqualByComparingTo("50");
		assertThat(r.get(3).pnl()).isEqualByComparingTo("25");
	}

	@Test
	void aBuyAndASellAtTheSameInstantCountsTheBuyFirst() {
		Map<Integer, Realized> r = TradeProfit.realized(List.of(sell(0, "A", 5, 105, 0), buy(1, "A", 5, 100, 0)));
		assertThat(r.get(0).pnl()).isEqualByComparingTo("25");
	}
}