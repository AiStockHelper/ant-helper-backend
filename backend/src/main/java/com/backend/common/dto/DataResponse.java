package com.backend.common.dto;

import org.springframework.http.HttpStatus;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.Getter;

@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DataResponse<T> extends BaseResponse {
	private final T data;

	private DataResponse(boolean isSuccess, HttpStatus status, T data) {
		super(isSuccess, status);
		this.data = data;
	}

	public static <T> DataResponse<T> from(T data) {
		return new DataResponse<>(true, HttpStatus.OK, data);
	}

	public static DataResponse<Void> ok() {
		return new DataResponse<>(true, HttpStatus.OK, null);
	}
}
