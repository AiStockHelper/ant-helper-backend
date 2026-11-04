package com.backend.domains.agent;

import java.util.LinkedHashMap;
import java.util.Map;

import com.backend.market.dto.MarketDtos.OrderOutcome;

/** 주문 결과를 채팅 카드(order_result)의 데이터로 바꾼다. */
public final class OrderCards {

	private OrderCards() {
	}

	public static Map<String, Object> orderResult(OrderOutcome o) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("success", o.success());
		m.put("message", o.message());
		m.put("orderNo", o.orderNo());
		m.put("side", o.side());
		m.put("productNumber", o.productNumber());
		m.put("name", o.name());
		m.put("market", o.market());
		m.put("currency", o.currency());
		m.put("quantity", o.quantity());
		m.put("price", o.price());
		return m;
	}
}
