package com.backend.common.dto;

import java.util.List;

import org.springframework.http.HttpStatus;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.backend.common.exception.ErrorCode;

import lombok.Getter;

@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse extends BaseResponse {
	private final String code;
	private final String message;
	private final List<String> reasons;

	private ErrorResponse(Boolean isSuccess, HttpStatus status, String code, String message) {
		super(isSuccess, status);
		this.message = message;
		this.code = code;
		this.reasons = null;
	}

	// Validation Error의 reasons 포함 생성자
	private ErrorResponse(Boolean isSuccess, HttpStatus status, String code, String message,
		List<String> reasons) {
		super(isSuccess, status);
		this.message = message;
		this.code = code;
		this.reasons = reasons;
	}

	public static ErrorResponse of(ErrorCode errorCode, List<String> reasons) {
		return new ErrorResponse(
			false,
			errorCode.getHttpStatus(),
			errorCode.getCode(),
			errorCode.getMessage(),
			reasons
		);
	}

	public static ErrorResponse from(ErrorCode errorCode) {
		return new ErrorResponse(
			false,
			errorCode.getHttpStatus(),
			errorCode.getCode(),
			errorCode.getMessage()
		);
	}

	public static ErrorResponse of(ErrorCode errorCode, String message) {
		return new ErrorResponse(false, errorCode.getHttpStatus(), errorCode.getCode(),  message);
	}
}
