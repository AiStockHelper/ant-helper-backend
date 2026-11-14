package com.backend.common.security.filter.jwtFilter;

import static com.backend.common.exception.ErrorCode.*;

import java.io.IOException;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.backend.common.exception.ApiException;
import com.backend.common.exception.ErrorCode;
import com.backend.common.security.SecurityPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

	private final JwtTokenProvider jwtTokenProvider;
	private final SecurityPolicy policy;
	private final AntPathMatcher pathMatcher;

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		String path = request.getRequestURI();
		return policy.permitUrls().stream().anyMatch(permitUrl -> pathMatcher.match(permitUrl, path));
	}

	/**
	 * 인증은 액세스 토큰 하나로만 한다. 리프레시 토큰은 이 필터가 보지 않고 /api/auth/refresh 에서만 쓴다
	 * (아무 요청에나 리프레시 헤더를 붙이면 토큰이 재발급되던 방식은 제거했다).
	 */
	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
		throws ServletException, IOException, ApiException {
		final String accessToken = jwtTokenProvider.extractAccessToken(request).orElse(null);
		if (accessToken == null) {
			log.warn("인증 정보 없음: {} {}", request.getMethod(), request.getRequestURI());
			throw ApiException.from(ErrorCode.UNAUTHORIZED);
		}
		Long memberId = jwtTokenProvider.validAccessMemberId(accessToken).orElse(null);
		if (memberId == null) {
			log.warn("access토큰 만료/무효: {} {}", request.getMethod(), request.getRequestURI());
			throw ApiException.from(ErrorCode.REISSUE_ACCESS_TOKEN);
		}
		if (jwtTokenProvider.isLogout(accessToken)) {
			log.warn("logout된 accessToken으로 인증 실패: {} {}", request.getMethod(), request.getRequestURI());
			throw ApiException.from(ErrorCode.INVALID_ACCESS_TOKEN);
		}
		Authentication authentication = jwtTokenProvider.getAuthentication(memberId);
		saveAuthentication(authentication);
		org.slf4j.MDC.put("memberId", String.valueOf(memberId));
		filterChain.doFilter(request, response);
	}

	private void saveAuthentication(Authentication authentication) {
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(authentication);
		SecurityContextHolder.setContext(context);
	}
}