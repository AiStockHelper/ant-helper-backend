package com.backend.common.util;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

public final class TimeUtil {

	public static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
	public static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

	private TimeUtil() {
	}

	/** 서버가 저장하는 LocalDateTime 은 모두 서울 기준이다. */
	public static OffsetDateTime kst(LocalDateTime t) {
		return t == null ? null : t.atZone(SEOUL).toOffsetDateTime();
	}

	public static OffsetDateTime kst(Instant t) {
		return t == null ? null : t.atZone(SEOUL).toOffsetDateTime();
	}
}
