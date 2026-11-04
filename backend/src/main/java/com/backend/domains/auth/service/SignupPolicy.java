package com.backend.domains.auth.service;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.backend.common.exception.ApiException;
import com.backend.common.exception.ErrorCode;
import com.backend.common.properties.AppProperties;

import lombok.RequiredArgsConstructor;

/**
 * 가입 허용 이메일 정책. {@code app.auth.allowed-signup-emails} 에 없는 이메일은 가입할 수 없다.
 * 목록이 비어 있으면 아무도 가입할 수 없다(설정 실수로 가입이 열리지 않도록).
 */
@Component
@RequiredArgsConstructor
public class SignupPolicy {

	private final AppProperties props;

	public void requireAllowed(String email) {
		if (!isAllowed(email)) {
			throw ApiException.from(ErrorCode.SIGNUP_NOT_ALLOWED);
		}
	}

	public boolean isAllowed(String email) {
		if (email == null || email.isBlank()) {
			return false;
		}
		Set<String> allowed = props.getAuth().getAllowedSignupEmails().stream()
			.map(e -> e.trim().toLowerCase(Locale.ROOT))
			.filter(e -> !e.isEmpty())
			.collect(Collectors.toSet());
		return allowed.contains(email.trim().toLowerCase(Locale.ROOT));
	}
}
