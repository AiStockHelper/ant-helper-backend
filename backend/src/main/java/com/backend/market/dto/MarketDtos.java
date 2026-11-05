package com.backend.market.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import com.backend.market.Currency;
import com.backend.market.Market;

/** DESIGN.md 3.2~3.3 / 5.5 의 응답 타입. 필드명은 프론트엔드와의 계약이므로 바꾸지 말 것. */
public final class MarketDtos {

	private MarketDtos() {
	}

	public record StockSummary(String productNumber, String name, String nameEn, Market market, Currency currency) {
	}

	public record Quote(
		String productNumber, String name, Market market, Currency currency,
		BigDecimal price, BigDecimal change, double changeRate,
		BigDecimal open, BigDecimal high, BigDecimal low, long volume, OffsetDateTime asOf
	) {
	}

	public record Candle(String date, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close, long volume) {
	}

	public record Signal(String name, String value, String tone) {
	}

	public record Prediction(
		String productNumber, String name, Market market, Currency currency,
		BigDecimal currentPrice, BigDecimal predictedPrice, double predictedChangeRate,
		String direction, double confidence, String summary, List<Signal> signals,
		String horizon, OffsetDateTime asOf
	) {
	}

	public record Recommendation(
		String productNumber, String name, Market market, Currency currency,
		BigDecimal currentPrice, double score, double predictedChangeRate,
		String direction, double confidence, List<String> reasons,
		/** 분석 기준 거래일(yyyy-MM-dd). 야간 배치 결과일 때만 값이 있다. */
		String asOf
	) {
	}

	public record Holding(
		String productNumber, String name, Market market, Currency currency,
		long quantity, long sellableQuantity, BigDecimal averagePrice, BigDecimal currentPrice,
		BigDecimal purchaseAmount, BigDecimal evaluationAmount, BigDecimal profitLoss, double profitLossRate
	) {
	}

	public record MarketSummary(
		Currency currency, BigDecimal cash,
		BigDecimal totalPurchaseAmount, BigDecimal totalEvaluationAmount, BigDecimal totalAssets,
		BigDecimal profitLoss, double profitLossRate, List<Holding> holdings
	) {
	}

	/** 국내 요약은 최상위 필드(기존 계약), 해외는 overseas 로 덧붙인다. */
	public record AccountSummary(
		Long accountId, Currency currency, BigDecimal cash,
		BigDecimal totalPurchaseAmount, BigDecimal totalEvaluationAmount, BigDecimal totalAssets,
		BigDecimal profitLoss, double profitLossRate, List<Holding> holdings,
		MarketSummary overseas, Double exchangeRate, OffsetDateTime asOf
	) {
	}

	public record OrderHistoryItem(
		String orderNo, OffsetDateTime orderedAt, String side,
		String productNumber, String name, Market market, Currency currency,
		long orderQuantity, BigDecimal orderPrice, long executedQuantity, BigDecimal averageExecutedPrice,
		String status
	) {
	}

	/** 주문 요청(내부용). limitPrice 가 null 이면 시장가(국내만). */
	public record OrderRequest(Market market, String productNumber, String side, long quantity, BigDecimal limitPrice) {
	}

	/**
	 * pending 이 true 면 아직 KIS 에 접수되지 않았지만 대기열에 등록되어 자동으로 다시 시도 중이라는 뜻이다
	 * (이때 success 는 false). 결과는 주문 큐 화면에서 확인한다.
	 */
	public record OrderOutcome(
		boolean success, String message, String orderNo,
		String side, String productNumber, String name, Market market, Currency currency,
		long quantity, BigDecimal price, boolean pending
	) {
		public OrderOutcome(boolean success, String message, String orderNo, String side, String productNumber, String name,
			Market market, Currency currency, long quantity, BigDecimal price) {
			this(success, message, orderNo, side, productNumber, name, market, currency, quantity, price, false);
		}

		public OrderOutcome asPending(String newMessage) {
			return new OrderOutcome(false, newMessage, orderNo, side, productNumber, name, market, currency, quantity, price, true);
		}
	}
}
