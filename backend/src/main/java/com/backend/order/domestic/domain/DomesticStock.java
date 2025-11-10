package com.backend.order.domestic.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DomesticStock {
	@Id
	@Column(name = "product_number")
	String productNumber;

	@Column(name = "name", nullable = false)
	String name;

	@Builder
	private DomesticStock(String productNumber, String name) {
		this.productNumber = productNumber;
		this.name = name;
	}
}
