package com.backend.market;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.backend.market.dto.MarketDtos.Candle;

class OpenSessionBarTest {

	private static Candle c(String date) {
		BigDecimal p = new BigDecimal("100");
		return new Candle(date, p, p, p, p, 1000);
	}

	@Test
	void todaysBarIsDroppedOnlyWhileTheMarketIsOpen() {
		ZonedDateTime now = ZonedDateTime.now(Market.KRX.zone());
		String today = LocalDate.now(Market.KRX.zone()).toString();
		List<Candle> candles = List.of(c("2000-01-03"), c(today));
		List<Candle> result = PredictionService.withoutOpenSessionBar(candles, Market.KRX);
		if (Market.KRX.isOpenAt(now)) {
			assertThat(result).hasSize(1);
		} else {
			assertThat(result).hasSize(2);
		}
	}

	@Test
	void olderBarsAreNeverDropped() {
		List<Candle> candles = List.of(c("2000-01-03"), c("2000-01-04"));
		assertThat(PredictionService.withoutOpenSessionBar(candles, Market.KRX)).hasSize(2);
		assertThat(PredictionService.withoutOpenSessionBar(List.of(), Market.NASDAQ)).isEmpty();
	}
}
