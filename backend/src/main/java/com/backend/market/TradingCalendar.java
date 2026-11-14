package com.backend.market;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.temporal.TemporalAdjusters;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.backend.common.properties.AppProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 거래일 판단. 주말과 휴장일을 반영한다.
 * - 미국(NYSE/NASDAQ): 정규 휴장일을 규칙으로 계산한다(조기 마감일은 반영하지 않는다).
 * - 한국(KRX): 고정 공휴일 + 대체공휴일 규칙은 내장, 음력 명절·선거일 등은 설정(app.calendar.krx-holidays)으로 받는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TradingCalendar {

	private final AppProperties props;

	private final Map<Integer, Set<LocalDate>> usCache = new ConcurrentHashMap<>();
	private volatile Set<LocalDate> configured;

	/** date 는 해당 시장 현지 날짜 */
	public boolean isTradingDay(Market market, LocalDate date) {
		DayOfWeek dow = date.getDayOfWeek();
		if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
			return false;
		}
		return market == Market.KRX ? !isKrxHoliday(date) : !usHolidays(date.getYear()).contains(date);
	}

	// ─────────────────────────── 한국 ───────────────────────────

	boolean isKrxHoliday(LocalDate date) {
		if (configuredHolidays().contains(date)) {
			return true;
		}
		int y = date.getYear();
		// 근로자의 날, 연말 휴장은 거래소 휴장일이다.
		if (date.equals(LocalDate.of(y, 1, 1)) || date.equals(LocalDate.of(y, 5, 1))
			|| date.equals(LocalDate.of(y, 6, 6)) || date.equals(LocalDate.of(y, 12, 25))) {
			return true;
		}
		if (date.getMonth() == Month.DECEMBER && date.getDayOfMonth() == 31) {
			return true;
		}
		// 대체공휴일이 적용되는 공휴일: 토·일요일과 겹치면 다음 월요일이 휴일
		for (LocalDate h : new LocalDate[] {LocalDate.of(y, 3, 1), LocalDate.of(y, 5, 5), LocalDate.of(y, 8, 15),
			LocalDate.of(y, 10, 3), LocalDate.of(y, 10, 9)}) {
			if (date.equals(substituteIfWeekend(h))) {
				return true;
			}
		}
		return false;
	}

	private static LocalDate substituteIfWeekend(LocalDate holiday) {
		return switch (holiday.getDayOfWeek()) {
			case SATURDAY -> holiday.plusDays(2);
			case SUNDAY -> holiday.plusDays(1);
			default -> holiday;
		};
	}

	private Set<LocalDate> configuredHolidays() {
		Set<LocalDate> set = configured;
		if (set == null) {
			Set<LocalDate> parsed = new HashSet<>();
			for (String s : props.getCalendar().getKrxHolidays()) {
				try {
					parsed.add(LocalDate.parse(s.trim()));
				} catch (RuntimeException e) {
					log.warn("휴장일 설정을 읽을 수 없습니다: {}", s);
				}
			}
			configured = set = parsed;
		}
		return set;
	}

	// ─────────────────────────── 미국 ───────────────────────────

	Set<LocalDate> usHolidays(int year) {
		return usCache.computeIfAbsent(year, TradingCalendar::computeUs);
	}

	private static Set<LocalDate> computeUs(int y) {
		Set<LocalDate> set = new HashSet<>();
		// 1/1 이 토요일이면 앞 영업일(전년 12/31)도 쉬지 않는다.
		LocalDate newYear = LocalDate.of(y, 1, 1);
		if (newYear.getDayOfWeek() != DayOfWeek.SATURDAY) {
			set.add(observed(newYear));
		}
		set.add(nth(y, Month.JANUARY, DayOfWeek.MONDAY, 3));
		set.add(nth(y, Month.FEBRUARY, DayOfWeek.MONDAY, 3));
		set.add(easter(y).minusDays(2));
		set.add(LocalDate.of(y, Month.MAY, 31).with(TemporalAdjusters.lastInMonth(DayOfWeek.MONDAY)));
		set.add(observed(LocalDate.of(y, 6, 19)));
		set.add(observed(LocalDate.of(y, 7, 4)));
		set.add(nth(y, Month.SEPTEMBER, DayOfWeek.MONDAY, 1));
		set.add(nth(y, Month.NOVEMBER, DayOfWeek.THURSDAY, 4));
		set.add(observed(LocalDate.of(y, 12, 25)));
		return set;
	}

	private static LocalDate observed(LocalDate d) {
		return switch (d.getDayOfWeek()) {
			case SATURDAY -> d.minusDays(1);
			case SUNDAY -> d.plusDays(1);
			default -> d;
		};
	}

	private static LocalDate nth(int year, Month month, DayOfWeek dow, int n) {
		return LocalDate.of(year, month, 1).with(TemporalAdjusters.dayOfWeekInMonth(n, dow));
	}

	/** 그레고리력 부활절(Anonymous Gregorian algorithm) */
	static LocalDate easter(int y) {
		int a = y % 19;
		int b = y / 100;
		int c = y % 100;
		int d = b / 4;
		int e = b % 4;
		int f = (b + 8) / 25;
		int g = (b - f + 1) / 3;
		int h = (19 * a + b - d - g + 15) % 30;
		int i = c / 4;
		int k = c % 4;
		int l = (32 + 2 * e + 2 * i - h - k) % 7;
		int m = (a + 11 * h + 22 * l) / 451;
		int month = (h + l - 7 * m + 114) / 31;
		int day = ((h + l - 7 * m + 114) % 31) + 1;
		return LocalDate.of(y, month, day);
	}
}
