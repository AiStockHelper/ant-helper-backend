package com.backend.common.util.keyGenerator;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import org.springframework.stereotype.Component;

@Component
public class KeyGenerator {

	private static final DateTimeFormatter FORMAT_YYYYMMDDHHMMSS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

	// 키는 앞에 날짜를 붙여 조회 성능 최적화
	public String generateKey() {
		return generateDate() + "_" + generateUUID();
	}

	private String generateUUID() {
		return UUID.randomUUID().toString().replace("-", "");
	}

	private String generateDate() {
		return FORMAT_YYYYMMDDHHMMSS.format(LocalDateTime.now());
	}
}
