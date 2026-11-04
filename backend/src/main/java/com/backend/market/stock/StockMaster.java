package com.backend.market.stock;

import com.backend.market.Market;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 종목 마스터. 이름 검색과 코드 → 시장 판별에 쓴다. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
	name = "stock_master",
	uniqueConstraints = @UniqueConstraint(name = "uk_stock_master_market_symbol", columnNames = {"market", "symbol"}),
	indexes = {
		@Index(name = "idx_stock_master_symbol", columnList = "symbol"),
		@Index(name = "idx_stock_master_name", columnList = "name")
	}
)
public class StockMaster {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Enumerated(EnumType.STRING)
	@Column(name = "market", nullable = false, length = 10)
	private Market market;

	@Column(name = "symbol", nullable = false, length = 20)
	private String symbol;

	@Column(name = "name", nullable = false, length = 200)
	private String name;

	@Column(name = "name_en", length = 200)
	private String nameEn;

	@Column(name = "security_type", length = 10)
	private String securityType;

	public StockMaster(Market market, String symbol, String name, String nameEn, String securityType) {
		this.market = market;
		this.symbol = symbol;
		this.name = name;
		this.nameEn = nameEn;
		this.securityType = securityType;
	}

	public void update(String name, String nameEn, String securityType) {
		this.name = name;
		this.nameEn = nameEn;
		this.securityType = securityType;
	}
}
