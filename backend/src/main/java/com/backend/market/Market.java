package com.backend.market;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Locale;

import com.backend.common.exception.ApiException;
import com.backend.common.exception.ErrorCode;

/**
 * 지원하는 거래 시장. 국내(KRX)와 미국(NASDAQ/NYSE/AMEX).
 * 다른 해외 시장은 {@link #quoteCode}/{@link #orderCode}/TR_ID 만 추가하면 확장할 수 있다.
 */
public enum Market {

	KRX("KRX", Currency.KRW, null, null, "Asia/Seoul", LocalTime.of(9, 0), LocalTime.of(15, 30)),
	NASDAQ("NASDAQ", Currency.USD, "NAS", "NASD", "America/New_York", LocalTime.of(9, 30), LocalTime.of(16, 0)),
	NYSE("NYSE", Currency.USD, "NYS", "NYSE", "America/New_York", LocalTime.of(9, 30), LocalTime.of(16, 0)),
	AMEX("AMEX", Currency.USD, "AMS", "AMEX", "America/New_York", LocalTime.of(9, 30), LocalTime.of(16, 0));

	private final String label;
	private final Currency currency;
	/** 해외 시세 API 의 EXCD */
	private final String quoteCode;
	/** 해외 주문 API 의 OVRS_EXCG_CD */
	private final String orderCode;
	private final ZoneId zone;
	private final LocalTime open;
	private final LocalTime close;

	Market(String label, Currency currency, String quoteCode, String orderCode, String zone, LocalTime open,
		LocalTime close) {
		this.label = label;
		this.currency = currency;
		this.quoteCode = quoteCode;
		this.orderCode = orderCode;
		this.zone = ZoneId.of(zone);
		this.open = open;
		this.close = close;
	}

	public Currency currency() {
		return currency;
	}

	public boolean overseas() {
		return this != KRX;
	}

	public String quoteCode() {
		return quoteCode;
	}

	public String orderCode() {
		return orderCode;
	}

	public ZoneId zone() {
		return zone;
	}

	public LocalTime openTime() {
		return open;
	}

	public LocalTime closeTime() {
		return close;
	}

	public String label() {
		return label;
	}

	/** 정규장 운영 중인지(주말만 제외하며 공휴일은 반영하지 않는다). */
	public boolean isOpenAt(ZonedDateTime instant) {
		ZonedDateTime local = instant.withZoneSameInstant(zone);
		DayOfWeek day = local.getDayOfWeek();
		if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
			return false;
		}
		LocalTime t = local.toLocalTime();
		return !t.isBefore(open) && t.isBefore(close);
	}

	public static Market parse(String value) {
		if (value == null || value.isBlank()) {
			throw ApiException.from(ErrorCode.MARKET_NOT_SUPPORTED);
		}
		String v = value.trim().toUpperCase(Locale.ROOT);
		return switch (v) {
			case "KRX", "KOSPI", "KOSDAQ", "KR", "KOREA" -> KRX;
			case "NASDAQ", "NAS", "NASD" -> NASDAQ;
			case "NYSE", "NYS" -> NYSE;
			case "AMEX", "AMS" -> AMEX;
			default -> Arrays.stream(values()).filter(m -> m.name().equals(v)).findFirst()
				.orElseThrow(() -> ApiException.from(ErrorCode.MARKET_NOT_SUPPORTED));
		};
	}
}
