package com.backend.common.security.filter.jwtFilter;

import java.util.Optional;

import org.springframework.security.core.Authentication;

import jakarta.servlet.http.HttpServletRequest;

public interface JwtTokenProvider {

	String createAccessToken(Long memberId);

	String createRefreshToken();

	Optional<String> extractAccessToken(HttpServletRequest request);

	/** 서명·만료·종류(액세스/리프레시)까지 맞는 토큰이면 회원 ID 를 돌려준다. */
	Optional<Long> validAccessMemberId(String accessToken);

	/** 서명과 만료, 리프레시 토큰 형식이 맞는지만 본다. 저장소에 있는지는 AuthService 가 확인한다. */
	boolean isValidRefreshToken(String refreshToken);

	Authentication getAuthentication(Long memberId);

	boolean isLogout(String accessToken);

	/** 로그아웃한 액세스 토큰을 만료 때까지 쓰지 못하게 한다. */
	void revoke(String accessToken);
}
