package com.backend.market;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.backend.market.dto.MarketDtos.OrderHistoryItem;

class TradingServiceOrderProfitTest {

	private static final ZoneOffset KST = ZoneOffset.ofHours(9);

	private static OrderHistoryItem order(String no, String side, String symbol, long exec, double avg, int day, String status) {
		return new OrderHistoryItem(no, OffsetDateTime.of(2026, 11, day, 10, 0, 0, 0, KST), side, symbol, "x", Market.KRX, Currency.KRW,
			exec, BigDecimal.ZERO, exec, BigDecimal.valueOf(avg), status);
	}

	@Test
	void sellsGetRealizedProfitAndBuysDoNot() {
		List<OrderHistoryItem> out = TradingService.attachRealized(List.of(
			order("1", "BUY", "005930", 10, 70000, 2, "FILLED"),
			order("2", "SELL", "005930", 4, 77000, 5, "FILLED")));
		assertThat(out.get(0).realizedPnl()).isNull();
		assertThat(out.get(0).executedAmount()).isEqualByComparingTo("700000");
		assertThat(out.get(1).costPrice()).isEqualByComparingTo("70000");
		assertThat(out.get(1).realizedPnl()).isEqualByComparingTo("28000");
		assertThat(out.get(1).realizedPnlRate()).isEqualTo(10.0);
		assertThat(out.get(1).executedAmount()).isEqualByComparingTo("308000");
	}

	@Test
	void unfilledOrRejectedOrdersAreNotTreatedAsTrades() {
		List<OrderHistoryItem> out = TradingService.attachRealized(List.of(
			order("1", "BUY", "005930", 10, 70000, 2, "FILLED"),
			order("2", "BUY", "005930", 0, 0, 3, "PENDING"),
			order("3", "SELL", "005930", 10, 99999, 4, "REJECTED"),         // 거부된 주문은 체결로 보지 않는다
			order("4", "SELL", "005930", 10, 63000, 5, "FILLED")));
		assertThat(out.get(2).realizedPnl()).isNull();
		assertThat(out.get(3).realizedPnl()).isEqualByComparingTo("-70000");
		assertThat(out.get(3).realizedPnlRate()).isEqualTo(-10.0);
	}

	@Test
	void aSellWhoseBuyIsOutsideTheLookedAtPeriodHasNoProfit() {
		List<OrderHistoryItem> out = TradingService.attachRealized(List.of(order("1", "SELL", "005930", 5, 80000, 5, "FILLED")));
		assertThat(out.get(0).realizedPnl()).isNull();
		assertThat(out.get(0).costPrice()).isNull();
	}

	@Test
	void sameSymbolInDifferentCurrenciesIsNotMixed() {
		OrderHistoryItem usBuy = new OrderHistoryItem("1", OffsetDateTime.of(2026, 11, 2, 10, 0, 0, 0, KST), "BUY", "005930", "x", Market.NASDAQ,
			Currency.USD, 1, BigDecimal.ONE, 1, BigDecimal.valueOf(100), "FILLED");
		List<OrderHistoryItem> out = TradingService.attachRealized(List.of(usBuy, order("2", "SELL", "005930", 1, 70000, 5, "FILLED")));
		assertThat(out.get(1).realizedPnl()).isNull();
	}

	@Test
	void lookbackExtendsToNinetyDaysButKeepsTheRecentOrderQueryForRecentRanges() {
		LocalDate today = LocalDate.of(2026, 11, 14);
		// 최근 1주일을 조회: 90일 앞은 3개월 전보다 오래돼서, 최근 3개월용 조회가 되는 가장 이른 날(3개월 전 다음 날)까지만 넓힌다
		assertThat(TradingService.lookbackStart(LocalDate.of(2026, 11, 7), today)).isEqualTo(LocalDate.of(2026, 8, 15));
		// 시작일이 3개월 전 경계 근처면 넓힐 여지가 없다
		assertThat(TradingService.lookbackStart(LocalDate.of(2026, 8, 15), today)).isEqualTo(LocalDate.of(2026, 8, 15));
		// 3개월보다 오래된 기간을 조회하면 그냥 90일 앞까지
		assertThat(TradingService.lookbackStart(LocalDate.of(2026, 5, 1), today)).isEqualTo(LocalDate.of(2026, 1, 31));
	}
}