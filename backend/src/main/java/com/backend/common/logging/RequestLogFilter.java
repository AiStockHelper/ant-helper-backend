package com.backend.common.logging;

import java.io.IOException;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * 모든 /api 요청에 추적 ID(reqId)를 붙이고, 끝날 때 한 줄로 결과를 남긴다.
 * memberId 는 JwtAuthenticationFilter 가 인증에 성공하면 MDC 에 넣는다.
 * 쿼리스트링과 Authorization 헤더는 토큰이 섞일 수 있어 기록하지 않는다.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestLogFilter extends OncePerRequestFilter {

	public static final String REQ_ID = "reqId";
	public static final String MEMBER_ID = "memberId";
	public static final String HEADER = "X-Request-Id";

	private static final long SLOW_MS = 3000;

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		String path = request.getRequestURI();
		return !path.startsWith("/api/") || path.startsWith("/api-docs");
	}

	@Override
	protected boolean shouldNotFilterAsyncDispatch() {
		return true;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
		throws ServletException, IOException {
		String reqId = sanitize(request.getHeader(HEADER));
		if (reqId == null) {
			reqId = UUID.randomUUID().toString().substring(0, 8);
		}
		MDC.put(REQ_ID, reqId);
		response.setHeader(HEADER, reqId);
		long start = System.currentTimeMillis();
		Throwable failure = null;
		try {
			chain.doFilter(request, response);
		} catch (IOException | ServletException | RuntimeException e) {
			failure = e;
			throw e;
		} finally {
			long ms = System.currentTimeMillis() - start;
			int status = failure != null ? 500 : response.getStatus();
			String line = "HTTP " + request.getMethod() + " " + request.getRequestURI() + " -> " + status + " " + ms
				+ "ms ip=" + clientIp(request);
			if (status >= 500) {
				log.error(line, failure);
			} else if (status >= 400) {
				log.warn(line);
			} else if (ms >= SLOW_MS) {
				log.warn("{} (느린 응답)", line);
			} else {
				log.info(line);
			}
			MDC.remove(REQ_ID);
			MDC.remove(MEMBER_ID);
		}
	}

	private static String sanitize(String v) {
		if (v == null) {
			return null;
		}
		String s = v.trim();
		return s.matches("[A-Za-z0-9_-]{1,40}") ? s : null;
	}

	private static String clientIp(HttpServletRequest request) {
		String xff = request.getHeader("X-Forwarded-For");
		if (xff != null && !xff.isBlank()) {
			return xff.split(",")[0].trim();
		}
		return request.getRemoteAddr();
	}
}
