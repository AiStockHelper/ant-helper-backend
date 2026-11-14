package com.backend.common.security.filter.exceptionHandlingFilter;

import java.io.IOException;
import java.util.List;

import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.backend.common.dto.ErrorResponse;
import com.backend.common.exception.ApiException;
import com.backend.common.exception.ErrorCode;
import com.backend.common.util.responseWriter.ResponseWriter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ExceptionHandlingFilter extends OncePerRequestFilter {
	@Override
	protected void doFilterInternal(
		HttpServletRequest request,
		HttpServletResponse response,
		FilterChain filterChain) throws ServletException, IOException {

		try {
			filterChain.doFilter(request, response);
		} catch (ApiException e) {
			log.warn("ExceptionHandlingFilter: {}", e.getMessage());

			ErrorResponse errorResponse = ErrorResponse.from(e.getErrorCode());
			ResponseWriter.writeResponse(response, errorResponse, e.getErrorCode().getHttpStatus());
		} catch (Exception e) {
			log.error("ExceptionHandlingFilter: 처리되지 않은 예외", e);
			ErrorCode errorCode = ErrorCode.INTERNAL_SERVER_ERROR;

			// 내부 예외 메시지는 응답에 싣지 않는다(로그에만 남긴다).
			ErrorResponse errorResponse = ErrorResponse.from(errorCode);

			ResponseWriter.writeResponse(response, errorResponse, errorCode.getHttpStatus());
		}
	}
}

