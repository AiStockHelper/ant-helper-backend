package com.backend.domains.screening;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.backend.common.properties.AppProperties;
import com.backend.infra.prediction.PredictionClient;
import com.backend.infra.prediction.PredictionClient.BatchSymbolIn;
import com.backend.market.Market;
import com.backend.market.stock.StockMaster;
import com.backend.market.stock.StockMasterRepository;

class ScreeningServiceTest {

	private ScreeningService service(StockMasterRepository masters) {
		return new ScreeningService(mock(PredictionClient.class), masters, mock(DailyScreenRepository.class),
			new AppProperties(), null, null, null);
	}

	private static StockMaster stock(Market m, String symbol, String name, String en) {
		return new StockMaster(m, symbol, name, en, "ST");
	}

	@Test
	void krxUniverseKeepsOnlyCommonShares() {
		StockMasterRepository masters = mock(StockMasterRepository.class);
		when(masters.findByMarketInAndSecurityType(anyCollection(), eq("ST"))).thenReturn(List.of(
			stock(Market.KRX, "005930", "삼성전자", null),
			stock(Market.KRX, "005935", "삼성전자우", null),          // 우선주(끝자리 5)
			stock(Market.KRX, "123450", "KB제1호스팩", null),         // 스팩
			stock(Market.KRX, "0000D0", "신규코드종목", null),         // 영숫자 코드, 보통주
			stock(Market.KRX, "12345", "다섯자리", null)));            // 형식 불량
		List<String> symbols = service(masters).universe(ScreeningService.KRX).stream().map(BatchSymbolIn::symbol).toList();
		assertThat(symbols).containsExactly("005930", "0000D0");
	}

	@Test
	void usUniverseDropsOddTickersWarrantsAndDuplicates() {
		StockMasterRepository masters = mock(StockMasterRepository.class);
		when(masters.findByMarketInAndSecurityType(anyCollection(), eq("ST"))).thenReturn(List.of(
			stock(Market.NASDAQ, "AAPL", "애플", "APPLE INC"),
			stock(Market.NYSE, "AAPL", "애플", "APPLE INC"),               // 중복 티커
			stock(Market.NYSE, "ABR$D", "우선주", "ARBOR PFD"),            // 특수문자
			stock(Market.NASDAQ, "XYZW", "워런트", "XYZ CORP WARRANT"),
			stock(Market.NASDAQ, "STRC", "스트래티지 우선주", "STRATEGY INC 10.00% SERIES A PERPETUAL STRETCH PREFERRED STOCK"),
			stock(Market.NASDAQ, "TOOLONG", "너무김", "TOO LONG"),
			stock(Market.NYSE, "JPM", "JP모건", "JPMORGAN CHASE")));
		List<BatchSymbolIn> out = service(masters).universe(ScreeningService.US);
		assertThat(out).extracting(BatchSymbolIn::symbol).containsExactly("AAPL", "JPM");
		assertThat(out.get(0).market()).isEqualTo("NASDAQ");
	}

	@Test
	void groupNormalizationMapsUsExchangesTogether() {
		assertThat(ScreeningService.normalizeGroup("nasdaq")).isEqualTo("US");
		assertThat(ScreeningService.normalizeGroup("NYSE")).isEqualTo("US");
		assertThat(ScreeningService.normalizeGroup("US")).isEqualTo("US");
		assertThat(ScreeningService.normalizeGroup(null)).isEqualTo("KRX");
		assertThat(ScreeningService.normalizeGroup("KOSPI")).isEqualTo("KRX");
	}
}
