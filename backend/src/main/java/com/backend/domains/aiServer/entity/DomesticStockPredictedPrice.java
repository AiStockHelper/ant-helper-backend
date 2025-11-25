package com.backend.domains.aiServer.entity;

import java.time.LocalDate;

import com.backend.common.entity.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// AI 서버에서 예측한 국내 주식 가격 정보를 저장하는 엔티티
@Entity
@Table(
	name = "domestic_stock_predicted_price",
	uniqueConstraints = {
		@UniqueConstraint(columnNames = {"product_number", "predicted_date"})
	}
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DomesticStockPredictedPrice extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;  // Django에는 없지만 JPA는 PK가 반드시 필요함

	@Column(name = "product_number", nullable = false, length = 100)
	private String productNumber;

	@Column(name = "today_price", nullable = false)
	private Double todayPrice;

	@Column(name = "predicted_date", nullable = false)
	private LocalDate predictedDate;

	@Column(name = "predicted_price", nullable = false)
	private Double predictedPrice;
}
