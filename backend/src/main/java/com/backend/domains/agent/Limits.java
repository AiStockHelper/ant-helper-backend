package com.backend.domains.agent;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 자율 주문의 한도. 서버 코드가 강제하며 AI 가 바꿀 수 없다.
 * 작업(과 그 작업이 만든 하위 작업)이 쓸 수 있는 "시드"를 정하고, 그 범위 안에서만 매매한다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Limits(
	/** 이 작업이 굴릴 수 있는 총 시드(해당 통화) */
	BigDecimal budget,
	/** KRW 또는 USD */
	String currency,
	/** 한 종목에 넣을 수 있는 최대 비중(시드 대비 %). 기본 30 */
	Double maxPositionPct,
	/** 하루 총 매수 한도. 기본 시드 전체 */
	BigDecimal maxDailyBuy,
	/** 누적 손실이 시드의 이 %에 도달하면 신규 매수를 막는다. 기본 15 */
	Double maxLossPct
) {

	public static final double DEFAULT_POSITION_PCT = 30;
	public static final double DEFAULT_LOSS_PCT = 15;

	public Limits normalized() {
		return new Limits(budget, currency == null ? "KRW" : currency,
			maxPositionPct == null ? DEFAULT_POSITION_PCT : maxPositionPct,
			maxDailyBuy == null ? budget : maxDailyBuy,
			maxLossPct == null ? DEFAULT_LOSS_PCT : maxLossPct);
	}

	public BigDecimal positionCap() {
		double pct = maxPositionPct == null ? DEFAULT_POSITION_PCT : maxPositionPct;
		return budget.multiply(BigDecimal.valueOf(pct / 100.0));
	}

	public BigDecimal lossLimitAmount() {
		double pct = maxLossPct == null ? DEFAULT_LOSS_PCT : maxLossPct;
		return budget.multiply(BigDecimal.valueOf(pct / 100.0));
	}

	public BigDecimal dailyBuyCap() {
		return maxDailyBuy == null ? budget : maxDailyBuy;
	}
}
