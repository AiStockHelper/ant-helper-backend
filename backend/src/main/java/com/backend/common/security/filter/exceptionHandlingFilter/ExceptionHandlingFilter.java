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
			log.warn("ExceptionHandlingFilter: {}", e.getMessage());
			ErrorCode errorCode = ErrorCode.INTERNAL_SERVER_ERROR;

			ErrorResponse errorResponse = ErrorResponse.of(errorCode, List.of(e.getMessage()));

			ResponseWriter.writeResponse(response, errorResponse, errorCode.getHttpStatus());
		}
	}
}

