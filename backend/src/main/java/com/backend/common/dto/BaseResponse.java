package com.backend.common.dto;

import java.time.LocalDateTime;

import org.springframework.http.HttpStatus;

import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.Getter;

@Getter
public abstract class BaseResponse {

	@JsonFormat(pattern = "yyyy-MM-dd kk:mm:ss")
	private final LocalDateTime timestamp = LocalDateTime.now();
	private final Boolean isSuccess;
	private final String status;
	private final Integer code;

	protected BaseResponse(Boolean isSuccess, HttpStatus status) {
		this.isSuccess = isSuccess;
		this.status = status.getReasonPhrase();
		this.code = status.value();
	}
}
