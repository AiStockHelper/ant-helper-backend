package com.backend.domains.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.backend.common.exception.ApiException;
import com.backend.common.properties.AppProperties;
import com.backend.domains.agent.skill.AiRiseSkill;
import com.backend.market.Market;
import com.backend.market.TradingCalendar;

class AgentScheduleTest {

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
	private static final ZoneId NY = ZoneId.of("America/New_York");

	private final TradingCalendar calendar = new TradingCalendar(new AppProperties());

	private static ZonedDateTime kst(int y, int m, int d, int h, int min) {
		return ZonedDateTime.of(y, m, d, h, min, 0, 0, SEOUL);
	}

	@Test
	void marketIntervalFiresEveryNMinutesDuringTheSessionOnly() {
		Schedule s = Schedule.marketInterval("KRX", 10, 5, -10);
		// 수요일 10:00 이후 첫 슬롯은 10:05 (09:05 부터 10분 간격)
		assertThat(s.next(kst(2026, 11, 4, 10, 0).toInstant(), calendar)).contains(kst(2026, 11, 4, 10, 5).toInstant());
		// 개장 전이면 그날 09:05
		assertThat(s.next(kst(2026, 11, 4, 8, 0).toInstant(), calendar)).contains(kst(2026, 11, 4, 9, 5).toInstant());
		// 마감 10분 전(15:20)이 마지막 슬롯 → 다음 날 아침
		assertThat(s.next(kst(2026, 11, 4, 15, 20).toInstant(), calendar)).contains(kst(2026, 11, 5, 9, 5).toInstant());
	}

	@Test
	void marketSchedulesSkipWeekendsAndHolidays() {
		Schedule s = Schedule.marketInterval("KRX", 10, 5, -10);
		// 2026-12-25(금 성탄절) 휴장, 주말 → 12-28(월)
		assertThat(s.next(kst(2026, 12, 24, 15, 30).toInstant(), calendar)).contains(kst(2026, 12, 28, 9, 5).toInstant());
		// 2026-10-03(토) 개천절 → 10-05(월) 대체공휴일, 10-06(화)에 재개
		assertThat(s.next(kst(2026, 10, 2, 15, 30).toInstant(), calendar)).contains(kst(2026, 10, 6, 9, 5).toInstant());
	}

	@Test
	void usOpenEventFollowsDaylightSavingTime() {
		Schedule s = new Schedule(Schedule.Type.MARKET_EVENT, null, null, Schedule.WEEKDAYS, null, "US", "OPEN", 10, null, null, null);
		// 서머타임(EDT): 뉴욕 09:40 = 한국 22:40
		assertThat(s.next(kst(2026, 7, 6, 12, 0).toInstant(), calendar))
			.contains(ZonedDateTime.of(2026, 7, 6, 9, 40, 0, 0, NY).toInstant());
		assertThat(ZonedDateTime.of(2026, 7, 6, 9, 40, 0, 0, NY).withZoneSameInstant(SEOUL).getHour()).isEqualTo(22);
		// 표준시(EST): 뉴욕 09:40 = 한국 23:40
		assertThat(s.next(kst(2026, 11, 3, 12, 0).toInstant(), calendar))
			.contains(ZonedDateTime.of(2026, 11, 3, 9, 40, 0, 0, NY).toInstant());
		assertThat(ZonedDateTime.of(2026, 11, 3, 9, 40, 0, 0, NY).withZoneSameInstant(SEOUL).getHour()).isEqualTo(23);
	}

	@Test
	void dailyScheduleHonoursWeekdaysAndTimezone() {
		Schedule s = new Schedule(Schedule.Type.DAILY, null, "09:00", Schedule.WEEKDAYS, null, null, null, null, null, null, null);
		// 금요일 10시 이후 → 월요일 09:00
		assertThat(s.next(kst(2026, 11, 6, 10, 0).toInstant(), calendar)).contains(kst(2026, 11, 9, 9, 0).toInstant());
		// 같은 날 아직 안 지났으면 오늘
		assertThat(s.next(kst(2026, 11, 4, 8, 0).toInstant(), calendar)).contains(kst(2026, 11, 4, 9, 0).toInstant());
		assertThat(s.describe()).isEqualTo("평일 09:00 (KST)");
	}

	@Test
	void onceHasNoNextAfterItsTime() {
		Schedule s = Schedule.once(kst(2026, 11, 5, 9, 0).toInstant());
		assertThat(s.next(kst(2026, 11, 4, 9, 0).toInstant(), calendar)).isPresent();
		assertThat(s.next(kst(2026, 11, 5, 9, 0).toInstant(), calendar)).isEmpty();
	}

	@Test
	void usHolidaysAreComputed() {
		assertThat(calendar.isTradingDay(Market.NASDAQ, LocalDate.of(2026, 11, 26))).isFalse(); // 추수감사절
		assertThat(calendar.isTradingDay(Market.NASDAQ, LocalDate.of(2026, 4, 3))).isFalse();   // 성금요일
		assertThat(calendar.isTradingDay(Market.NASDAQ, LocalDate.of(2026, 7, 3))).isFalse();   // 독립기념일(토) 대체 휴장
		assertThat(calendar.isTradingDay(Market.NASDAQ, LocalDate.of(2026, 11, 27))).isTrue();
	}

	@Test
	void koreanHolidaysIncludeFixedSubstituteAndConfiguredDays() {
		assertThat(calendar.isTradingDay(Market.KRX, LocalDate.of(2026, 5, 1))).isFalse();   // 근로자의 날
		assertThat(calendar.isTradingDay(Market.KRX, LocalDate.of(2026, 2, 17))).isFalse();  // 설날(설정)
		assertThat(calendar.isTradingDay(Market.KRX, LocalDate.of(2026, 8, 17))).isFalse();  // 광복절 대체공휴일
		assertThat(calendar.isTradingDay(Market.KRX, LocalDate.of(2026, 12, 31))).isFalse(); // 연말 휴장
		assertThat(calendar.isTradingDay(Market.KRX, LocalDate.of(2026, 11, 4))).isTrue();
	}

	@Test
	void parseDaysUnderstandsCommonForms() {
		assertThat(Schedule.parseDays("WEEKDAYS")).containsExactly("MON", "TUE", "WED", "THU", "FRI");
		assertThat(Schedule.parseDays("평일")).containsExactly("MON", "TUE", "WED", "THU", "FRI");
		assertThat(Schedule.parseDays("EVERYDAY")).isNull();
		assertThat(Schedule.parseDays("mon, wed")).containsExactly("MON", "WED");
		assertThat(Schedule.parseDays("월,금")).containsExactly("MON", "FRI");
		assertThatThrownBy(() -> Schedule.parseDays("blah")).isInstanceOf(ApiException.class);
	}

	@Test
	void validationRejectsUnsafeSchedules() {
		assertThatThrownBy(() -> Schedule.marketInterval("KRX", 3, 5, -10).validate())
			.isInstanceOf(ApiException.class).hasMessageContaining("5분");
		assertThatThrownBy(() -> new Schedule(Schedule.Type.DAILY, null, "25:99", null, null, null, null, null, null, null, null).validate())
			.isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> new Schedule(Schedule.Type.MARKET_EVENT, null, null, null, null, null, "OPEN", 0, null, null, null).validate())
			.isInstanceOf(ApiException.class).hasMessageContaining("market");
		assertThatThrownBy(() -> new Schedule(Schedule.Type.ONCE, "내일 아침", null, null, null, null, null, null, null, null, null).validate())
			.isInstanceOf(ApiException.class).hasMessageContaining("형식");
	}

	@Test
	void aiRiseSkillFillsDefaultsAndRejectsOutOfRangeValues() {
		AiRiseSkill skill = new AiRiseSkill();
		Map<String, Object> cfg = skill.normalize(Map.of());
		assertThat(cfg).containsEntry("market", "KRX").containsEntry("maxPositions", 5).containsEntry("takeProfitPct", 2.0);
		Limits limits = skill.limits(cfg);
		assertThat(limits.currency()).isEqualTo("KRW");
		assertThat(limits.budget()).isEqualByComparingTo("5000000");
		assertThat(limits.positionCap()).isEqualByComparingTo("1000000");
		assertThat(skill.schedule(cfg).describe()).contains("한국장").contains("10분마다");

		assertThatThrownBy(() -> skill.normalize(Map.of("market", "JP"))).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> skill.normalize(Map.of("takeProfitPct", 99))).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> skill.normalize(Map.of("budget", "abc"))).isInstanceOf(ApiException.class);
		assertThat(skill.normalize(Map.of("market", "US")).get("budget")).isEqualTo(3000.0);
	}

	@Test
	void limitsHaveSafeDefaults() {
		Limits l = new Limits(new java.math.BigDecimal("1000000"), null, null, null, null).normalized();
		assertThat(l.currency()).isEqualTo("KRW");
		assertThat(l.positionCap()).isEqualByComparingTo("300000");
		assertThat(l.lossLimitAmount()).isEqualByComparingTo("150000");
		assertThat(l.dailyBuyCap()).isEqualByComparingTo("1000000");
		assertThat(List.of(l)).hasSize(1);
	}
}
