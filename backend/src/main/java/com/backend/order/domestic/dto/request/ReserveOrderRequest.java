package com.backend.order.domestic.dto.request;

import java.time.LocalDate;

import org.springframework.ai.tool.annotation.ToolParam;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

public record ReserveOrderRequest(
	@NotBlank(message = "종목코드는 필수입니다")
	@Pattern(regexp = "^[0-9]{6}$", message = "종목코드는 6자리 숫자여야 합니다")
	String productNumber,
	@NotNull(message = "주문수량은 필수입니다")
	@Positive(message = "주문수량은 양수여야 합니다")
	Integer quantity,
	@NotNull(message = "주문가격은 필수입니다")
	@ToolParam(description = "01 : 매도, 02 : 매수")
	String sellOrBuy,
	@NotNull(message = "주문단가는 필수입니다")
	@ToolParam(description = "1주당 가격, 장전 시간외, 시장가의 경우 1주당 가격을 공란으로 비우지 않음 '0'으로 입력 권고")
	Integer orderUnitPrice,
	@NotNull(message = "주문구분코드는 필수입니다")
	@ToolParam(description = "주문구분코드, 00 : 지정가, 01 : 시장가, 02 : 조건부지정가, 05 : 장전 시간외")
	String orderDivisionCode,
	@NotNull(message = "예약주문종료일자는 필수입니다")
	@ToolParam(description = "예약주문종료일자 (현재 일자 이후), 예약주문종료일자를 안 넣으면 다음날 주문처리되고 예약주문은 종료됨, 예약주문종료일자는 익영업일부터 달력일 기준으로 공휴일 포함하여 최대 30일이 되는 일자까지 입력 가능")
	LocalDate reservationOrderEndDate // 예약주문종료일자 (현재 일자 이후)
) {
}
