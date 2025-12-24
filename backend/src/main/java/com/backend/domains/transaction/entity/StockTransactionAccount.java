package com.backend.domains.transaction.entity;

import static com.backend.common.exception.ErrorCode.*;

import com.backend.common.exception.ApiException;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 유저 계좌 중 자사 서비스가 관리하는 계좌 잔액
 */
@Table(name = "stock_transaction_accounts")
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockTransactionAccount {

	@Id
	@Column(name = "id")
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "member_id", nullable = false, unique = true)
	private Long memberId;

	// Ledger가 관리하는 version
	@Column(name = "ledger_version", nullable = false)
	private Long ledgerVersion = 0L;

	// 관리 긍액
	@Column(name = "posted_balance", nullable = false)
	private Long postedBalance = 0L;

	/**
	 * 사용자 아이디로 계좌 생성
	 * 코인은 기본값 0으로 설정
	 */
	@Builder
	private StockTransactionAccount(Long memberId) {
		this.memberId = memberId;
	}

	// 잔액 업데이트
	public void updateBalance(Long newBalance) {
		if (newBalance < 0) {
			throw new ApiException(BALANCE_NOT_ENOUGH_EXCEPTION);
		}

		this.postedBalance = newBalance;
		this.ledgerVersion++; // Balance 변경 시마다 version 증가
	}
}
