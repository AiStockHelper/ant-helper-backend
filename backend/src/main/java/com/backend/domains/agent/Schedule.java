package com.backend.domains.agent;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import com.backend.common.exception.ApiException;
import com.backend.common.exception.ErrorCode;
import com.backend.common.util.TimeUtil;
import com.backend.market.Market;
import com.backend.market.TradingCalendar;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 자동 실행 일정. 네 가지 형태가 있다.
 * <ul>
 *   <li>ONCE: 정해진 시각에 한 번 ({@code runAt})</li>
 *   <li>DAILY: 매일/요일별 정해진 시각 ({@code time}, {@code days}, {@code zone}). market 을 주면 그 시장의 휴장일은 건너뛴다.</li>
 *   <li>MARKET_EVENT: 장 시작/마감 기준 N분 전후 ({@code market}, {@code event}, {@code offsetMinutes}). 서머타임·휴장일이 자동 반영된다.</li>
 *   <li>MARKET_INTERVAL: 장중 N분마다 ({@code market}, {@code intervalMinutes}, 개장+start ~ 마감+end)</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Schedule(
	Type type,
	String runAt,
	String time,
	List<String> days,
	String zone,
	String market,
	String event,
	Integer offsetMinutes,
	Integer intervalMinutes,
	Integer startOffsetMinutes,
	Integer endOffsetMinutes
) {

	public enum Type {ONCE, DAILY, MARKET_EVENT, MARKET_INTERVAL}

	public static final int MIN_INTERVAL_MINUTES = 5;
	private static final int SEARCH_DAYS = 21;
	private static final List<String> DOW = List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN");
	private static final List<String> DOW_KO = List.of("월", "화", "수", "목", "금", "토", "일");
	public static final List<String> WEEKDAYS = List.of("MON", "TUE", "WED", "THU", "FRI");

	// ─────────────────────────── 생성 도우미 ───────────────────────────

	public static Schedule once(Instant at) {
		return new Schedule(Type.ONCE, at.toString(), null, null, null, null, null, null, null, null, null);
	}

	public static Schedule marketInterval(String market, int intervalMinutes, int startOffset, int endOffset) {
		return new Schedule(Type.MARKET_INTERVAL, null, null, WEEKDAYS, null, market, null, null, intervalMinutes,
			startOffset, endOffset);
	}

	/** 평일 장 시작/마감 기준 N분 전후(offset 이 음수면 전). */
	public static Schedule marketEvent(String market, String event, int offsetMinutes) {
		return new Schedule(Type.MARKET_EVENT, null, null, WEEKDAYS, null, market, event, offsetMinutes, null, null, null);
	}

	/** "WEEKDAYS", "EVERYDAY", "MON,WED", "월,수,금" 같은 입력을 요일 목록으로. 비어 있거나 매일이면 null(=매일). */
	public static List<String> parseDays(String text) {
		if (text == null || text.isBlank()) {
			return null;
		}
		String t = text.trim().toUpperCase(Locale.ROOT);
		if (t.contains("EVERY") || t.contains("DAILY") || t.equals("ALL") || t.contains("매일")) {
			return null;
		}
		if (t.contains("WEEKDAY") || t.contains("평일")) {
			return WEEKDAYS;
		}
		if (t.contains("WEEKEND") || t.contains("주말")) {
			return List.of("SAT", "SUN");
		}
		List<String> out = new ArrayList<>();
		for (String token : t.split("[,\\s·/]+")) {
			if (token.isBlank()) {
				continue;
			}
			String key = token.length() >= 3 ? token.substring(0, 3) : token;
			int idx = DOW.indexOf(key);
			if (idx < 0) {
				idx = DOW_KO.indexOf(token.substring(0, 1));
			}
			if (idx < 0) {
				throw ApiException.of(ErrorCode.AGENT_INVALID, "요일을 알 수 없습니다: " + token);
			}
			if (!out.contains(DOW.get(idx))) {
				out.add(DOW.get(idx));
			}
		}
		return out.size() == 7 || out.isEmpty() ? null : out;
	}

	// ─────────────────────────── 시장/시간대 ───────────────────────────

	public Market marketEnum() {
		if (market == null || market.isBlank()) {
			return null;
		}
		String m = market.trim().toUpperCase(Locale.ROOT);
		return m.equals("US") || m.equals("USA") ? Market.NASDAQ : Market.parse(m);
	}

	public ZoneId zoneId() {
		if (zone != null && !zone.isBlank()) {
			try {
				return ZoneId.of(zone.trim());
			} catch (RuntimeException e) {
				throw ApiException.of(ErrorCode.AGENT_INVALID, "시간대를 알 수 없습니다: " + zone);
			}
		}
		Market m = marketEnum();
		return m != null ? m.zone() : TimeUtil.SEOUL;
	}

	// ─────────────────────────── 검증 ───────────────────────────

	public void validate() {
		if (type == null) {
			throw ApiException.of(ErrorCode.AGENT_INVALID, "일정 종류(type)가 필요합니다.");
		}
		zoneId();
		switch (type) {
			case ONCE -> parseOnce();
			case DAILY -> {
				parseTime();
				if (market != null && !market.isBlank()) {
					marketEnum();
				}
			}
			case MARKET_EVENT -> {
				requireMarket();
				if (event == null || !(event.equalsIgnoreCase("OPEN") || event.equalsIgnoreCase("CLOSE"))) {
					throw ApiException.of(ErrorCode.AGENT_INVALID, "event 는 OPEN 또는 CLOSE 여야 합니다.");
				}
				int off = offsetMinutes == null ? 0 : offsetMinutes;
				if (off < -240 || off > 240) {
					throw ApiException.of(ErrorCode.AGENT_INVALID, "개장/마감 기준 오프셋은 ±240분 이내여야 합니다.");
				}
			}
			case MARKET_INTERVAL -> {
				Market m = requireMarket();
				int every = intervalMinutes == null ? 0 : intervalMinutes;
				if (every < MIN_INTERVAL_MINUTES || every > 240) {
					throw ApiException.of(ErrorCode.AGENT_INVALID,
						"반복 간격은 " + MIN_INTERVAL_MINUTES + "분 이상 240분 이하여야 합니다.");
				}
				long session = Duration.between(m.openTime(), m.closeTime()).toMinutes();
				int s = startOffset();
				int e = endOffset();
				if (s < 0 || e > 0 || s - e >= session) {
					throw ApiException.of(ErrorCode.AGENT_INVALID, "장중 반복 구간(개장+시작 ~ 마감+종료)이 올바르지 않습니다.");
				}
			}
		}
		if (days != null) {
			for (String d : days) {
				if (!DOW.contains(d)) {
					throw ApiException.of(ErrorCode.AGENT_INVALID, "요일 값이 올바르지 않습니다: " + d);
				}
			}
		}
	}

	private Market requireMarket() {
		Market m = marketEnum();
		if (m == null) {
			throw ApiException.of(ErrorCode.AGENT_INVALID, "market(KRX 또는 US)이 필요합니다.");
		}
		return m;
	}

	public Instant parseOnce() {
		if (runAt == null || runAt.isBlank()) {
			throw ApiException.of(ErrorCode.AGENT_INVALID, "실행 시각(runAt)이 필요합니다.");
		}
		String t = runAt.trim();
		try {
			return OffsetDateTime.parse(t).toInstant();
		} catch (DateTimeParseException e) {
			try {
				return Instant.parse(t);
			} catch (DateTimeParseException e1) {
				try {
					return LocalDateTime.parse(t.replace(' ', 'T')).atZone(zoneId()).toInstant();
				} catch (DateTimeParseException e2) {
					throw ApiException.of(ErrorCode.AGENT_INVALID,
						"실행 시각 형식이 올바르지 않습니다. 예: 2026-10-25T09:00 (" + runAt + ")");
				}
			}
		}
	}

	private LocalTime parseTime() {
		try {
			return LocalTime.parse(time == null ? "" : time.trim());
		} catch (DateTimeParseException e) {
			throw ApiException.of(ErrorCode.AGENT_INVALID, "시각 형식은 HH:mm 이어야 합니다: " + time);
		}
	}

	private int startOffset() {
		return startOffsetMinutes == null ? 5 : startOffsetMinutes;
	}

	private int endOffset() {
		return endOffsetMinutes == null ? -10 : endOffsetMinutes;
	}

	// ─────────────────────────── 다음 실행 시각 ───────────────────────────

	private boolean dayAllowed(LocalDate d) {
		return days == null || days.isEmpty() || days.contains(DOW.get(d.getDayOfWeek().getValue() - 1));
	}

	/** after 이후(포함하지 않음)의 가장 가까운 실행 시각. 더 이상 없으면 empty. */
	public Optional<Instant> next(Instant after, TradingCalendar calendar) {
		switch (type) {
			case ONCE -> {
				Instant at = parseOnce();
				return at.isAfter(after) ? Optional.of(at) : Optional.empty();
			}
			case DAILY -> {
				ZoneId z = zoneId();
				LocalTime t = parseTime();
				Market m = marketEnum();
				LocalDate d = after.atZone(z).toLocalDate();
				for (int i = 0; i < SEARCH_DAYS; i++, d = d.plusDays(1)) {
					if (!dayAllowed(d) || (m != null && !calendar.isTradingDay(m, d))) {
						continue;
					}
					Instant c = d.atTime(t).atZone(z).toInstant();
					if (c.isAfter(after)) {
						return Optional.of(c);
					}
				}
				return Optional.empty();
			}
			case MARKET_EVENT -> {
				Market m = requireMarket();
				boolean open = "OPEN".equalsIgnoreCase(event);
				int off = offsetMinutes == null ? 0 : offsetMinutes;
				LocalDate d = after.atZone(m.zone()).toLocalDate().minusDays(1);
				for (int i = 0; i < SEARCH_DAYS; i++, d = d.plusDays(1)) {
					if (!dayAllowed(d) || !calendar.isTradingDay(m, d)) {
						continue;
					}
					ZonedDateTime base = d.atTime(open ? m.openTime() : m.closeTime()).atZone(m.zone()).plusMinutes(off);
					if (base.toInstant().isAfter(after)) {
						return Optional.of(base.toInstant());
					}
				}
				return Optional.empty();
			}
			case MARKET_INTERVAL -> {
				Market m = requireMarket();
				long step = intervalMinutes * 60L;
				LocalDate d = after.atZone(m.zone()).toLocalDate();
				for (int i = 0; i < SEARCH_DAYS; i++, d = d.plusDays(1)) {
					if (!dayAllowed(d) || !calendar.isTradingDay(m, d)) {
						continue;
					}
					Instant start = d.atTime(m.openTime()).atZone(m.zone()).plusMinutes(startOffset()).toInstant();
					Instant end = d.atTime(m.closeTime()).atZone(m.zone()).plusMinutes(endOffset()).toInstant();
					Instant cand;
					if (after.isBefore(start)) {
						cand = start;
					} else {
						long k = (after.getEpochSecond() - start.getEpochSecond()) / step + 1;
						cand = start.plusSeconds(k * step);
					}
					if (!cand.isAfter(end)) {
						return Optional.of(cand);
					}
				}
				return Optional.empty();
			}
		}
		return Optional.empty();
	}

	// ─────────────────────────── 사람이 읽는 설명 ───────────────────────────

	private static final DateTimeFormatter HUMAN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

	public String describe() {
		return switch (type) {
			case ONCE -> "1회 · " + parseOnce().atZone(TimeUtil.SEOUL).format(HUMAN) + " (KST)";
			case DAILY -> daysLabel() + " " + time + " (" + zoneLabel() + ")"
				+ (marketEnum() != null ? " · " + marketLabel() + " 휴장일 제외" : "");
			case MARKET_EVENT -> {
				int off = offsetMinutes == null ? 0 : offsetMinutes;
				String ev = "OPEN".equalsIgnoreCase(event) ? "개장" : "마감";
				String rel = off == 0 ? ev + " 시각" : ev + " " + Math.abs(off) + "분 " + (off > 0 ? "후" : "전");
				yield daysLabel() + " " + marketLabel() + " " + rel + " · 휴장일 제외";
			}
			case MARKET_INTERVAL -> daysLabel() + " " + marketLabel() + " 장중 " + intervalMinutes + "분마다 (개장 +"
				+ startOffset() + "분 ~ 마감 " + endOffset() + "분) · 휴장일 제외";
		};
	}

	private String zoneLabel() {
		ZoneId z = zoneId();
		return z.equals(TimeUtil.SEOUL) ? "KST" : z.getId();
	}

	private String marketLabel() {
		Market m = marketEnum();
		return m == null ? "" : m == Market.KRX ? "한국장" : "미국장";
	}

	private String daysLabel() {
		if (days == null || days.isEmpty() || days.size() == 7) {
			return "매일";
		}
		if (Set.copyOf(days).equals(Set.copyOf(WEEKDAYS))) {
			return "평일";
		}
		if (Set.copyOf(days).equals(Set.of("SAT", "SUN"))) {
			return "주말";
		}
		List<String> ko = new ArrayList<>();
		for (String d : DOW) {
			if (days.contains(d)) {
				ko.add(DOW_KO.get(DOW.indexOf(d)));
			}
		}
		return String.join("·", ko);
	}
}
