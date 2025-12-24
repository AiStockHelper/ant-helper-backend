package com.backend.domains.transaction.entity;

import static com.backend.common.exception.ErrorCode.*;
import static jakarta.persistence.EnumType.*;

import java.time.LocalDateTime;

import com.backend.common.entity.BaseEntity;
import com.backend.common.exception.ApiException;
import com.backend.domains.transaction.enums.OrdererType;
import com.backend.domains.transaction.enums.StockTransactionDirection;
import com.backend.domains.transaction.enums.StockTransactionStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Transaction 거래의 단위 거래
 */
@Table(name = "stock_transactions")
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockTransaction extends BaseEntity {

	@Id
	@Column(name = "id", updatable = false)
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "account_id", nullable = false, updatable = false)
	private Long accountId;

	@Column(name = "product_number")
	String productNumber;

	@Column(name = "order_amount")
	Long orderAmount;

	@Column(name = "direction", nullable = false, updatable = false)
	@Enumerated(STRING)
	private StockTransactionDirection stockTransactionDirection;

	@Column(name = "status", nullable = false, updatable = false)
	@Enumerated(STRING)
	private StockTransactionStatus stockTransactionStatus;

	// 해당 주식 관리 주체
	@Column(name = "orderer_type")
	@Enumerated(STRING)
	OrdererType ordererType;

	@Column(name = "account_version", nullable = false, updatable = false)
	private Long accountVersion;

	@Column(name = "discarded_at", nullable = true)
	private LocalDateTime discardedAt; // 취소되지 않았으면 null

	@Column(name = "effective_at", nullable = true)
	private LocalDateTime effectiveAt; // 거래가 실제로 반영된 시간

	private StockTransaction(
		Long accountId,
		String productNumber,
		Long orderAmount,
		StockTransactionDirection stockTransactionDirection,
		StockTransactionStatus stockTransactionStatus,
		OrdererType ordererType,
		Long accountVersion,
		LocalDateTime effectiveAt
	) {
		this.accountId = accountId;
		this.productNumber = productNumber;
		this.orderAmount = orderAmount;
		this.stockTransactionDirection = stockTransactionDirection;
		this.stockTransactionStatus = stockTransactionStatus;
		this.ordererType = ordererType;
		this.accountVersion = accountVersion;
		this.effectiveAt = effectiveAt;
	}

	// 매매 요청 주문내역 저장(체결되기 전에 요청, 체결 후에는 별도 저장)
	public static StockTransaction createPendingTransaction(
		Long accountId,
		String productNumber,
		Long orderAmount,
		StockTransactionDirection stockTransactionDirection,
		OrdererType ordererType,
		Long accountVersion
	) {
		return new StockTransaction(
			accountId,
			productNumber,
			orderAmount,
			stockTransactionDirection,
			StockTransactionStatus.PENDING,
			ordererType,
			accountVersion,
			null
		);
	}

	// 거래가 실제로 반영된 시간도 함께 설정
	// Posted를 생성 시에는 기존 Pending 거래의 discardedAt 설정해야 함
	public static StockTransaction createPostedTransaction(
		Long accountId,
		String productNumber,
		Long orderAmount,
		StockTransactionDirection stockTransactionDirection,
		OrdererType ordererType,
		Long accountVersion,
		LocalDateTime effectiveAt
	) {
		// effectiveAt null 체크
		if (effectiveAt == null) {
			throw ApiException.from(EFFECTIVE_AT_CANT_NOT_BE_NULL);
		}

		return new StockTransaction(
			accountId,
			productNumber,
			orderAmount,
			stockTransactionDirection,
			StockTransactionStatus.POSTED,
			ordererType,
			accountVersion,
			effectiveAt
		);
	}

	// 거래 취소하기
	// 별도로 취소 상태의 CoinTransactionEntity 생성해야 함
	public void discardTransaction(LocalDateTime discardedAt) {
		// 이미 취소된 거래는 다시 취소할 수 없음
		if (this.discardedAt != null) {
			throw ApiException.from(DUPLICATED_DISCARD_EXCEPTION);
		}
		// discardedAt은 null일 수 없음
		if (discardedAt == null) {
			throw ApiException.from(EFFECTIVE_AT_CANT_NOT_BE_NULL);
		}

		this.discardedAt = discardedAt;
	}
}
