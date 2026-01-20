package com.backend.domains.transaction.dto.response;

import java.time.LocalDateTime;

import com.backend.domains.transaction.entity.StockTransaction;
import com.backend.domains.transaction.enums.OrdererType;
import com.backend.domains.transaction.enums.StockTransactionDirection;
import com.backend.domains.transaction.enums.StockTransactionStatus;

public record GetTransactionEntityResponse(
	String productNumber,
	long amount,
	StockTransactionDirection direction,
	StockTransactionStatus status,
	LocalDateTime effectiveAt,
	OrdererType ordererType
) {
	public static GetTransactionEntityResponse from(StockTransaction entity) {
		return new GetTransactionEntityResponse(
			entity.getProductNumber(),
			entity.getOrderAmount(),
			entity.getStockTransactionDirection(),
			entity.getStockTransactionStatus(),
			entity.getEffectiveAt(),
			entity.getOrdererType()
		);
	}
}
