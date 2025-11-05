package com.backend.common.exception;

import lombok.Getter;

@Getter
public class ApiException extends RuntimeException {

	private final ErrorCode errorCode;

	public ApiException(ErrorCode errorCode, String message) {
		super(message);
		this.errorCode = errorCode;
	}

	public ApiException(ErrorCode errorCode) {
		super(errorCode.getMessage());
		this.errorCode = errorCode;
	}

	public static ApiException from(ErrorCode errorCode) {
		return new ApiException(errorCode, errorCode.getMessage());
	}

	// 메시지를 커스터마이징 할 수 있는 생성자
	public static ApiException of(ErrorCode errorCode, String message) {
		return new ApiException(errorCode, message);
	}
}