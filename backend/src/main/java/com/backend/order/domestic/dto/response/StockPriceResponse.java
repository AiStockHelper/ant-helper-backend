package com.backend.order.domestic.dto.response;

import java.math.BigDecimal;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class StockPriceResponse {
	private String symbol;
	private String name;
	private BigDecimal currentPrice;
	private BigDecimal priceChange;
	private BigDecimal changeRate;
	private Long volume;
	private String currency;
	private String market;
}