package com.backend.order.domestic.dto.request;

import java.math.BigDecimal;

import com.backend.order.domestic.enums.ExchangeType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class DomesticTradeRequest {
	@NotBlank(message = "종목코드는 필수입니다")
	@Pattern(regexp = "^[0-9]{6}$", message = "종목코드는 6자리 숫자여야 합니다")
	private String productNumber;

	@NotNull(message = "주문수량은 필수입니다")
	@Positive(message = "주문수량은 양수여야 합니다")
	private Integer quantity;

	@NotNull(message = "주문가격은 필수입니다")
	@Positive(message = "주문가격은 양수여야 합니다")
	private BigDecimal price;

	private String orderType = "00"; // 기본값: 지정가

	@NotNull(message = "거래소 유형은 필수입니다, KRX 또는 NXT여야 합니다")
	private ExchangeType exchangeType;
}