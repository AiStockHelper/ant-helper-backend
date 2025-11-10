package com.backend.order.domestic.dto.request;

import com.backend.order.domestic.domain.DomesticStock;

public record GetDomesticStocksResponse(
	String productNumber,
	String name
) {
	public static GetDomesticStocksResponse from(DomesticStock domesticStock) {
		return new GetDomesticStocksResponse(
			domesticStock.getProductNumber(),
			domesticStock.getName()
		);
	}
}
