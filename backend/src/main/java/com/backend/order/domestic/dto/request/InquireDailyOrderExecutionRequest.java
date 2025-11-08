package com.backend.order.domestic.dto.request;

import java.time.LocalDate;

import com.backend.order.domestic.enums.ExchangeType;

import jakarta.validation.constraints.NotNull;

public record InquireDailyOrderExecutionRequest(
	@NotNull(message = "조회 시작일은 필수입니다")
	LocalDate inquireStartDate,
	@NotNull(message = "조회 종료일은 필수입니다")
	LocalDate inquireEndDate,
	@NotNull(message = "거래소 유형은 필수입니다, KRX 또는 NXT여야 합니다")
	ExchangeType exchangeType
) {
}
