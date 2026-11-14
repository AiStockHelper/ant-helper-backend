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

	/**
	 * 기준 자산(원금) 대비 손익. amount 는 기준 자산, pnl 은 (현재 총자산 − 기준 자산), pnlRate 는 pnl ÷ 기준 자산(%).
	 * manual 이 false 면 서비스가 계좌를 처음 조회한 시점의 총자산을 자동으로 기준으로 삼은 것이다(입출금은 반영하지 못한다).
	 */
	public record BaselineInfo(BigDecimal amount, OffsetDateTime at, boolean manual, BigDecimal pnl, double pnlRate) {
	}

	/**
	 * profitLoss/profitLossRate 는 '지금 보유한 종목'의 평가손익(평가금액 − 매입금액)이다.
	 * 이미 판 종목의 손익까지 포함한 전체 손익은 baseline(기준 자산 대비)에서 본다.
	 */
	public record MarketSummary(
		Currency currency, BigDecimal cash,
		BigDecimal totalPurchaseAmount, BigDecimal totalEvaluationAmount, BigDecimal totalAssets,
		BigDecimal profitLoss, double profitLossRate, List<Holding> holdings,
		BaselineInfo baseline
	) {
		public MarketSummary(Currency currency, BigDecimal cash, BigDecimal totalPurchaseAmount, BigDecimal totalEvaluationAmount,
			BigDecimal totalAssets, BigDecimal profitLoss, double profitLossRate, List<Holding> holdings) {
			this(currency, cash, totalPurchaseAmount, totalEvaluationAmount, totalAssets, profitLoss, profitLossRate, holdings, null);
		}

		public MarketSummary withBaseline(BaselineInfo b) {
			return new MarketSummary(currency, cash, totalPurchaseAmount, totalEvaluationAmount, totalAssets, profitLoss, profitLossRate,
				holdings, b);
		}
	}

	/** 국내 요약은 최상위 필드(기존 계약), 해외는 overseas 로 덧붙인다. baseline 은 국내(원화) 기준 자산 대비 손익이다. */
	public record AccountSummary(
		Long accountId, Currency currency, BigDecimal cash,
		BigDecimal totalPurchaseAmount, BigDecimal totalEvaluationAmount, BigDecimal totalAssets,
		BigDecimal profitLoss, double profitLossRate, List<Holding> holdings,
		MarketSummary overseas, Double exchangeRate, OffsetDateTime asOf,
		BaselineInfo baseline
	) {
		public AccountSummary(Long accountId, Currency currency, BigDecimal cash, BigDecimal totalPurchaseAmount,
			BigDecimal totalEvaluationAmount, BigDecimal totalAssets, BigDecimal profitLoss, double profitLossRate,
			List<Holding> holdings, MarketSummary overseas, Double exchangeRate, OffsetDateTime asOf) {
			this(accountId, currency, cash, totalPurchaseAmount, totalEvaluationAmount, totalAssets, profitLoss, profitLossRate, holdings,
				overseas, exchangeRate, asOf, null);
		}

		public AccountSummary withBaselines(BaselineInfo domestic, MarketSummary overseasWithBaseline) {
			return new AccountSummary(accountId, currency, cash, totalPurchaseAmount, totalEvaluationAmount, totalAssets, profitLoss,
				profitLossRate, holdings, overseasWithBaseline, exchangeRate, asOf, domestic);
		}
	}

	/**
	 * 매도 체결에는 실현손익이 붙는다(조회 기간과 그 앞 3개월 안의 체결로 이동평균 원가를 계산; 수수료·세금 제외).
	 * 원가를 알 수 없는 매도, 그리고 매수에는 costPrice/realizedPnl/realizedPnlRate 가 null 이다.
	 */
	public record OrderHistoryItem(
		String orderNo, OffsetDateTime orderedAt, String side,
		String productNumber, String name, Market market, Currency currency,
		long orderQuantity, BigDecimal orderPrice, long executedQuantity, BigDecimal averageExecutedPrice,
		String status,
		BigDecimal executedAmount, BigDecimal costPrice, BigDecimal realizedPnl, Double realizedPnlRate
	) {
		public OrderHistoryItem(String orderNo, OffsetDateTime orderedAt, String side, String productNumber, String name, Market market,
			Currency currency, long orderQuantity, BigDecimal orderPrice, long executedQuantity, BigDecimal averageExecutedPrice,
			String status) {
			this(orderNo, orderedAt, side, productNumber, name, market, currency, orderQuantity, orderPrice, executedQuantity,
				averageExecutedPrice, status,
				averageExecutedPrice == null ? null : averageExecutedPrice.multiply(BigDecimal.valueOf(executedQuantity)), null, null, null);
		}

		public OrderHistoryItem withProfit(BigDecimal costPrice, BigDecimal realizedPnl, Double realizedPnlRate) {
			return new OrderHistoryItem(orderNo, orderedAt, side, productNumber, name, market, currency, orderQuantity, orderPrice,
				executedQuantity, averageExecutedPrice, status, executedAmount, costPrice, realizedPnl, realizedPnlRate);
		}
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
