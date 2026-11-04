package com.backend.domains.screening;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import com.backend.market.Market;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 야간 배치가 분석한 종목별 결과. 날짜별로 쌓아 두므로 나중에 예측 적중률을 검증하는 데도 쓸 수 있다. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "daily_screen",
	uniqueConstraints = @UniqueConstraint(name = "uk_daily_screen", columnNames = {"trade_date", "market_group", "symbol"}),
	indexes = @Index(name = "idx_daily_screen_rank", columnList = "market_group, trade_date, score"))
public class DailyScreen {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "trade_date", nullable = false)
	private LocalDate tradeDate;

	/** KRX 또는 US */
	@Column(name = "market_group", nullable = false, length = 4)
	private String marketGroup;

	@Enumerated(EnumType.STRING)
	@Column(name = "market", nullable = false, length = 10)
	private Market market;

	@Column(name = "symbol", nullable = false, length = 20)
	private String symbol;

	@Column(name = "name", length = 200)
	private String name;

	@Column(name = "price", nullable = false, precision = 20, scale = 4)
	private BigDecimal price;

	@Column(name = "score", nullable = false)
	private double score;

	@Column(name = "predicted_change_rate", nullable = false)
	private double predictedChangeRate;

	@Column(name = "direction", nullable = false, length = 8)
	private String direction;

	@Column(name = "confidence", nullable = false)
	private double confidence;

	@Column(name = "reasons_json", columnDefinition = "LONGTEXT")
	private String reasonsJson;

	@Column(name = "avg_trade_value")
	private Double avgTradeValue;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	public DailyScreen(LocalDate tradeDate, String marketGroup, Market market, String symbol, String name, BigDecimal price,
		double score, double predictedChangeRate, String direction, double confidence, String reasonsJson, Double avgTradeValue) {
		this.tradeDate = tradeDate;
		this.marketGroup = marketGroup;
		this.market = market;
		this.symbol = symbol;
		this.name = name;
		this.price = price;
		this.score = score;
		this.predictedChangeRate = predictedChangeRate;
		this.direction = direction;
		this.confidence = confidence;
		this.reasonsJson = reasonsJson;
		this.avgTradeValue = avgTradeValue;
	}

	@PrePersist
	void onCreate() {
		createdAt = LocalDateTime.now();
	}
}
