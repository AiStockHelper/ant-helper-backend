package com.backend.common.dto;

import java.time.LocalDateTime;

import org.springframework.http.HttpStatus;

import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.Getter;

@Getter
public abstract class BaseResponse {

	private final Boolean isSuccess;
	private final String status;

	protected BaseResponse(Boolean isSuccess, HttpStatus status) {
		this.isSuccess = isSuccess;
		this.status = status.getReasonPhrase();
	}
}
