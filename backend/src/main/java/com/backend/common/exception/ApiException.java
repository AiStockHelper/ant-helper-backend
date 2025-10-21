package com.backend.common.exception;

import org.springframework.http.HttpStatus;

import lombok.Getter;

@Getter
public class ApiException extends RuntimeException {

	private final HttpStatus httpStatus;

	public ApiException(HttpStatus httpStatus, String message) {
		super(message);
		this.httpStatus = httpStatus;
	}

	public static ApiException from(ErrorCode errorCode) {
		return new ApiException(errorCode.getHttpStatus(), errorCode.getMessage());
	}

	public static ApiException of(HttpStatus httpStatus, String message) {
		return new ApiException(httpStatus, message);
	}
}