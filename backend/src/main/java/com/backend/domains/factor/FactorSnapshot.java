package com.backend.domains.factor;

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

/**
 * 멀티팩터 스킬이 그날 평가한 종목의 요인 값을 남겨 둔 기록.
 * 수급·밸류에이션은 과거 데이터를 구할 수 없어 백테스트를 못 했으므로, 여기에 쌓인 값과 이후 수익률을 비교해
 * 요인이 실제로 효과가 있는지 나중에 검증하는 용도다. 주문과는 무관하다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "factor_snapshot",
	uniqueConstraints = @UniqueConstraint(name = "uk_factor_snapshot", columnNames = {"trade_date", "symbol"}),
	indexes = @Index(name = "idx_factor_snapshot_symbol", columnList = "symbol, trade_date"))
public class FactorSnapshot {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "trade_date", nullable = false)
	private LocalDate tradeDate;

	@Enumerated(EnumType.STRING)
	@Column(name = "market", nullable = false, length = 10)
	private Market market;

	@Column(name = "symbol", nullable = false, length = 20)
	private String symbol;

	@Column(name = "name", length = 200)
	private String name;

	@Column(name = "price", precision = 20, scale = 4)
	private BigDecimal price;

	/** 기술(가격·거래량) 요인 점수: 야간 스크리닝의 요인 순위 점수 */
	@Column(name = "tech_score")
	private Double techScore;

	/** (외국인+기관 순매수) / 거래대금, 최근 N거래일. 데이터가 없으면 null */
	@Column(name = "flow_ratio")
	private Double flowRatio;

	@Column(name = "foreign_net")
	private Double foreignNet;

	@Column(name = "org_net")
	private Double orgNet;

	@Column(name = "per")
	private Double per;

	@Column(name = "pbr")
	private Double pbr;

	/** 이 스킬이 계산한 종합 점수(-100~100) */
	@Column(name = "composite")
	private Double composite;

	/** 실제로 매수 대상으로 골랐는지 */
	@Column(name = "picked", nullable = false)
	private boolean picked;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	public FactorSnapshot(LocalDate tradeDate, Market market, String symbol, String name, BigDecimal price, Double techScore,
		Double flowRatio, Double foreignNet, Double orgNet, Double per, Double pbr, Double composite, boolean picked) {
		this.tradeDate = tradeDate;
		this.market = market;
		this.symbol = symbol;
		this.name = name;
		this.price = price;
		this.techScore = techScore;
		this.flowRatio = flowRatio;
		this.foreignNet = foreignNet;
		this.orgNet = orgNet;
		this.per = per;
		this.pbr = pbr;
		this.composite = composite;
		this.picked = picked;
	}

	@PrePersist
	void onCreate() {
		createdAt = LocalDateTime.now();
	}
}
