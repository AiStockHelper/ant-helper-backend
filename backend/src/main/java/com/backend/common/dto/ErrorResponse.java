package com.backend.common.dto;

import java.util.List;

import org.springframework.http.HttpStatus;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.backend.common.exception.ErrorCode;
import lombok.Getter;

@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse extends BaseResponse {
	private final String message;
	private final List<String> reasons;

	private ErrorResponse(Boolean isSuccess, HttpStatus status, String message) {
		super(isSuccess, status);
		this.message = message;
		this.reasons = null;
	}

	private ErrorResponse(Boolean isSuccess, HttpStatus status, String message, List<String> reasons) {
		super(isSuccess, status);
		this.message = message;
		this.reasons = reasons;
	}

	public static ErrorResponse of(ErrorCode errorCode, List<String> reasons) {
		Boolean isSuccess = false;
		HttpStatus status = errorCode.getHttpStatus();
		String message = errorCode.getMessage();

		return new ErrorResponse(isSuccess, status, message, reasons);
	}

	public static ErrorResponse from(ErrorCode errorCode) {
		Boolean isSuccess = false;
		HttpStatus status = errorCode.getHttpStatus();
		String message = errorCode.getMessage();

		return new ErrorResponse(isSuccess, status, message);
	}

	public static ErrorResponse of(HttpStatus httpStatus, String message) {
		Boolean isSuccess = false;
		return new ErrorResponse(isSuccess, httpStatus, message);
	}
}
