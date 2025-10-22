package com.backend.order.domestic.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class OrderStockRequest {

	@JsonProperty("PDNO")
	private String PDNO; // 종목코드 (6자리, 최대 12자리)

	@JsonProperty("ORD_DVSN")
	private String ORD_DVSN; // 주문구분 (2자리)

	@JsonProperty("ORD_QTY")
	private String ORD_QTY; // 주문수량 (최대 10자리)

	@JsonProperty("ORD_UNPR")
	private String ORD_UNPR; // 주문단가 (최대 19자리)
}