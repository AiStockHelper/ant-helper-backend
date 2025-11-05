package com.backend.domains.watchList.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

import com.backend.domains.watchList.enums.MarketType;

import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
	name = "watch_list",
	uniqueConstraints = {
		@UniqueConstraint(columnNames = {"member_id", "product_number", "market_type"})
	}
)
public class WatchList {

	@Id
	@Column(name = "id")
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "member_id", updatable = false, nullable = false)
	private Long memberId;

	@Column(name = "product_number", updatable = false, nullable = false)
	private String productNumber;

	@Column(name = "market_type", updatable = false, nullable = false)
	@Enumerated(EnumType.STRING)
	private MarketType marketType;

	@Builder
	private WatchList(final Long memberId, final String productNumber, final MarketType marketType) {
		this.memberId = memberId;
		this.productNumber = productNumber;
		this.marketType = marketType;
	}
}
