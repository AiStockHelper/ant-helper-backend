package com.backend.domains.autoTrade.entity;

import java.time.LocalDateTime;

import com.backend.common.entity.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
	name = "auto_trade_meta_data"
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AutoTradeMetaData extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "operate_amount", nullable = false)
	private Long operateAmount; // 운용금액

	@Column(name = "used_amount", nullable = false)
	private Long usedAmount; // 사용금액

	@Column(name = "end_date", nullable = true)
	private LocalDateTime endDate; // 자동매매 종료일시

	public AutoTradeMetaData(Long operateAmount, Long usedAmount) {
		this.operateAmount = operateAmount;
		this.usedAmount = usedAmount;
	}

	// 사용금액 반영
	public void addUsedAmount(Long usedAmount) {
		this.usedAmount = usedAmount;
	}
}
