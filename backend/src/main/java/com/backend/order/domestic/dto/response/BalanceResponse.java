package com.backend.order.domestic.dto.response;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.backend.order.kis.kis_api.api.rest.trading.InquireBalanceResult;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class BalanceResponse {
	private List<HoldingStock> holdings;
	private BigDecimal totalAssetValue;
	private BigDecimal availableCash;
	private BigDecimal totalProfitLoss;
	private BigDecimal totalProfitLossRate;

	@Getter
	@NoArgsConstructor(access = AccessLevel.PRIVATE)
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	@Builder
	public static class HoldingStock {
		private String symbol;
		private String name;
		private Integer quantity;
		private BigDecimal averagePrice;
		private BigDecimal currentPrice;
		private BigDecimal evaluationAmount;
		private BigDecimal profitLoss;
		private BigDecimal profitLossRate;
		private String currency;
	}

	// 잔고 조회 결과를 BalanceResponse로 변환
	public static BalanceResponse from(InquireBalanceResult result) {
		List<HoldingStock> holdings = new ArrayList<>();
		BigDecimal totalAssetValue = BigDecimal.ZERO;
		BigDecimal totalProfitLoss = BigDecimal.ZERO;

		// output1에서 보유 종목 정보 추출
		if (result.getOutput1() != null) {
			for (InquireBalanceResult.Output1 item : result.getOutput1()) {
				if (Integer.parseInt(item.getHldgQty()) > 0) { // 보유 수량이 0보다 큰 경우만
					HoldingStock holding = HoldingStock.builder()
						.symbol(item.getPdno())
						.name(item.getPrdtName())
						.quantity(Integer.parseInt(item.getHldgQty()))
						.averagePrice(new BigDecimal(item.getPchsAvgPric()))
						.currentPrice(new BigDecimal(item.getPrpr()))
						.evaluationAmount(new BigDecimal(item.getEvluAmt()))
						.profitLoss(new BigDecimal(item.getEvluPflsAmt()))
						.profitLossRate(new BigDecimal(item.getEvluPflsRt()))
						.currency("KRW")
						.build();

					holdings.add(holding);
					totalAssetValue = totalAssetValue.add(holding.getEvaluationAmount());
					totalProfitLoss = totalProfitLoss.add(holding.getProfitLoss());
				}
			}
		}

		// output2에서 계좌 전체 정보 추출
		BigDecimal availableCash = BigDecimal.ZERO;
		if (result.getOutput2() != null && result.getOutput2().length > 0) {
			InquireBalanceResult.Output2 accountInfo = result.getOutput2()[0];
			availableCash = new BigDecimal(accountInfo.getDncaTotAmt());
		}

		BigDecimal totalProfitLossRate = totalAssetValue.compareTo(BigDecimal.ZERO) > 0
			? totalProfitLoss.divide(totalAssetValue.subtract(totalProfitLoss), 4, java.math.RoundingMode.HALF_UP)
			.multiply(BigDecimal.valueOf(100))
			: BigDecimal.ZERO;

		return BalanceResponse.builder()
			.holdings(holdings)
			.totalAssetValue(totalAssetValue)
			.availableCash(availableCash)
			.totalProfitLoss(totalProfitLoss)
			.totalProfitLossRate(totalProfitLossRate)
			.build();
	}
}