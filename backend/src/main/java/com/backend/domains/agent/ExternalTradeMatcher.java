package com.backend.domains.agent;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import com.backend.market.Market;
import com.backend.market.dto.MarketDtos.OrderHistoryItem;

/**
 * 한국투자증권 체결 내역에서 '이 앱이 낸 주문이 아닌 것'(한투 앱·HTS 등에서 직접 한 주문)을 골라내 장부에 반영할 값을 계산한다.
 * 주문번호는 하루 단위로 매겨질 수 있어 (주문일, 주문번호) 쌍으로 비교한다. 순수 계산이라 단독으로 테스트할 수 있다.
 */
final class ExternalTradeMatcher {

	private ExternalTradeMatcher() {
	}

	/** 앱 밖에서 체결된 매도 중 장부 수량에 대응시킨 결과 */
	record Attribution(long quantity, BigDecimal averagePrice, String firstOrderNo, int orders) {
	}

	/** 이 앱이 이미 장부에 기록한 주문을 가리키는 키 */
	static String key(LocalDate orderDate, String orderNo) {
		return orderDate + ":" + (orderNo == null ? "" : orderNo.trim());
	}

	/**
	 * since 이후 symbol 종목의 매도 체결 중 앱이 기록하지 않은 것을 오래된 순서로 maxQty 만큼 모아 평균 체결가를 돌려준다.
	 * 해당하는 체결이 없으면 null.
	 */
	static Attribution externalSells(List<OrderHistoryItem> history, Set<String> knownKeys, Market market, String symbol,
		Instant since, long maxQty, ZoneId zone) {
		if (history == null || maxQty <= 0) {
			return null;
		}
		List<OrderHistoryItem> candidates = new ArrayList<>();
		for (OrderHistoryItem h : history) {
			if (h == null || !"SELL".equals(h.side()) || h.market() != market || h.executedQuantity() <= 0
				|| h.averageExecutedPrice() == null || h.averageExecutedPrice().signum() <= 0
				|| !h.productNumber().equalsIgnoreCase(symbol)) {
				continue;
			}
			if (h.orderedAt() != null && since != null && h.orderedAt().toInstant().isBefore(since)) {
				continue;
			}
			LocalDate date = h.orderedAt() == null ? null : h.orderedAt().atZoneSameInstant(zone).toLocalDate();
			if (knownKeys.contains(key(date, h.orderNo()))) {
				continue;
			}
			candidates.add(h);
		}
		candidates.sort(Comparator.comparing(OrderHistoryItem::orderedAt, Comparator.nullsLast(Comparator.naturalOrder())));

		long remaining = maxQty;
		long taken = 0;
		BigDecimal value = BigDecimal.ZERO;
		String first = null;
		int orders = 0;
		for (OrderHistoryItem h : candidates) {
			if (remaining <= 0) {
				break;
			}
			long q = Math.min(h.executedQuantity(), remaining);
			value = value.add(h.averageExecutedPrice().multiply(BigDecimal.valueOf(q)));
			taken += q;
			remaining -= q;
			orders++;
			if (first == null) {
				first = h.orderNo();
			}
		}
		if (taken <= 0) {
			return null;
		}
		return new Attribution(taken, value.divide(BigDecimal.valueOf(taken), 4, RoundingMode.HALF_UP), first, orders);
	}

	/** symbol 종목에 아직 체결되지 않은(대기·일부체결) 매수 주문이 있는가. 있으면 계좌 수량이 곧 늘 것이므로 판단을 미룬다. */
	static boolean hasPendingBuy(List<OrderHistoryItem> history, Market market, String symbol) {
		if (history == null) {
			return false;
		}
		return history.stream().anyMatch(h -> h != null && "BUY".equals(h.side()) && h.market() == market
			&& h.productNumber().equalsIgnoreCase(symbol) && ("PENDING".equals(h.status()) || "PARTIAL".equals(h.status())));
	}
}
