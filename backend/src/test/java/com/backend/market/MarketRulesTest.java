package com.backend.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.Test;

import com.backend.common.exception.ApiException;
import com.backend.domains.schedule.ScheduleService;
import com.backend.domains.trade.TradePlanner;
import com.backend.market.stock.StockMasterLoader;

class MarketRulesTest {

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
	private static final ZoneId NY = ZoneId.of("America/New_York");

	@Test
	void krxIsOpenOnWeekdaysDuringRegularHoursOnly() {
		assertThat(Market.KRX.isOpenAt(ZonedDateTime.of(2026, 10, 26, 10, 0, 0, 0, SEOUL))).isTrue();   // 월 10:00
		assertThat(Market.KRX.isOpenAt(ZonedDateTime.of(2026, 10, 26, 8, 59, 0, 0, SEOUL))).isFalse();  // 개장 전
		assertThat(Market.KRX.isOpenAt(ZonedDateTime.of(2026, 10, 26, 15, 30, 0, 0, SEOUL))).isFalse(); // 마감 시각 포함 안 함
		assertThat(Market.KRX.isOpenAt(ZonedDateTime.of(2026, 10, 24, 11, 0, 0, 0, SEOUL))).isFalse();  // 토
	}

	@Test
	void usMarketUsesNewYorkTimeAcrossDst() {
		// 같은 한국 시각이어도 서머타임 여부에 따라 개장 여부가 달라진다.
		ZonedDateTime summer = ZonedDateTime.of(2026, 7, 6, 22, 30, 0, 0, SEOUL);  // 뉴욕 09:30 EDT
		ZonedDateTime winter = ZonedDateTime.of(2026, 12, 7, 22, 30, 0, 0, SEOUL); // 뉴욕 08:30 EST
		assertThat(Market.NASDAQ.isOpenAt(summer)).isTrue();
		assertThat(Market.NASDAQ.isOpenAt(winter)).isFalse();
		assertThat(Market.NASDAQ.isOpenAt(ZonedDateTime.of(2026, 12, 7, 9, 30, 0, 0, NY))).isTrue();
		assertThat(Market.NASDAQ.isOpenAt(ZonedDateTime.of(2026, 12, 7, 16, 0, 0, 0, NY))).isFalse();
	}

	@Test
	void parseAcceptsAliasesAndRejectsUnknown() {
		assertThat(Market.parse("nasdaq")).isEqualTo(Market.NASDAQ);
		assertThat(Market.parse("NAS")).isEqualTo(Market.NASDAQ);
		assertThat(Market.parse("KOSPI")).isEqualTo(Market.KRX);
		assertThatThrownBy(() -> Market.parse("TOKYO")).isInstanceOf(ApiException.class);
	}

	@Test
	void currencyFormatsForHumans() {
		assertThat(Currency.KRW.format(new BigDecimal("71200"))).isEqualTo("₩71,200");
		assertThat(Currency.USD.format(new BigDecimal("189.5"))).isEqualTo("$189.50");
		assertThat(Currency.USD.round(new BigDecimal("1.005"))).isEqualByComparingTo("1.01");
	}

	@Test
	void sideIsNormalizedFromKoreanAndEnglish() {
		assertThat(TradePlanner.normalizeSide("매수")).isEqualTo("BUY");
		assertThat(TradePlanner.normalizeSide("buy")).isEqualTo("BUY");
		assertThat(TradePlanner.normalizeSide("매도")).isEqualTo("SELL");
		assertThat(TradePlanner.normalizeSide(" SELL ")).isEqualTo("SELL");
		assertThatThrownBy(() -> TradePlanner.normalizeSide("hold")).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> TradePlanner.normalizeSide(null)).isInstanceOf(ApiException.class);
	}

	@Test
	void scheduleRejectsPastWeekendAndMalformedTimes() {
		ScheduleService s = new ScheduleService(null, null, null, null, null, null, null, null, null, null);
		LocalDateTime nextWeekday = nextWeekdayAtNine();

		Instant ok = s.parseRunAt(nextWeekday.toString(), Market.KRX);
		assertThat(ok).isEqualTo(nextWeekday.atZone(SEOUL).toInstant());

		assertThatThrownBy(() -> s.parseRunAt("2020-01-06T09:00", Market.KRX))
			.isInstanceOf(ApiException.class).hasMessageContaining("뒤여야");
		assertThatThrownBy(() -> s.parseRunAt("내일 아침", Market.KRX))
			.isInstanceOf(ApiException.class).hasMessageContaining("형식");
		assertThatThrownBy(() -> s.parseRunAt(nextSaturdayAtNine().toString(), Market.KRX))
			.isInstanceOf(ApiException.class).hasMessageContaining("토요일");
		assertThatThrownBy(() -> s.parseRunAt(LocalDateTime.now(SEOUL).plusDays(90).toString(), Market.KRX))
			.isInstanceOf(ApiException.class);
	}

	@Test
	void masterFileTailLengthIsDetectedEvenWhenFormatShifts() {
		String[] rows = new String[50];
		for (int i = 0; i < rows.length; i++) {
			// 단축코드(9) + 표준코드(12) + 종목명 + [그룹코드(ST) + 나머지 고정폭 220자] => 꼬리 222자
			String head = String.format("%-9s%-12s%s", "00" + (1000 + i), "KR7000000000", "테스트종목" + i);
			rows[i] = head + "ST" + "0".repeat(220);
		}
		// 기대값을 일부러 1 틀리게 줘도 데이터에서 실제 길이를 찾아야 한다.
		assertThat(StockMasterLoader.detectTailLength(rows, 223)).isEqualTo(222);
		assertThat(StockMasterLoader.detectTailLength(rows, 222)).isEqualTo(222);
	}

	private static LocalDateTime nextWeekdayAtNine() {
		LocalDateTime t = LocalDateTime.now(SEOUL).plusDays(1).withHour(9).withMinute(0).withSecond(0).withNano(0);
		while (t.getDayOfWeek().getValue() >= 6) {
			t = t.plusDays(1);
		}
		return t;
	}

	private static LocalDateTime nextSaturdayAtNine() {
		LocalDateTime t = LocalDateTime.now(SEOUL).plusDays(1).withHour(9).withMinute(0).withSecond(0).withNano(0);
		while (t.getDayOfWeek().getValue() != 6) {
			t = t.plusDays(1);
		}
		return t;
	}
}
