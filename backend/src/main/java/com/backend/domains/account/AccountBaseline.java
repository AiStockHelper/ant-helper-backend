package com.backend.domains.account;

import java.math.BigDecimal;
import java.time.Instant;

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

/**
 * 계좌의 '기준 자산(원금)'. 총자산이 기준보다 얼마 늘었는지(손익과 수익률)를 보여 주는 데 쓴다.
 * 증권사 API 는 투자 원금을 알려 주지 않아서, 서비스가 계좌를 처음 조회한 시점의 총자산을 자동으로 기록하고(manual=false)
 * 사용자가 직접 정할 수도 있게(manual=true) 했다. 통화(KRW/USD)별로 하나씩 둔다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "account_baseline", uniqueConstraints = @UniqueConstraint(name = "uk_account_baseline", columnNames = {"account_id", "currency"}))
public class AccountBaseline {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "account_id", nullable = false)
	private Long accountId;

	@Column(name = "currency", nullable = false, length = 3)
	private String currency;

	@Column(name = "amount", nullable = false, precision = 20, scale = 4)
	private BigDecimal amount;

	@Column(name = "baseline_at", nullable = false)
	private Instant baselineAt;

	@Column(name = "manual", nullable = false)
	private boolean manual;

	public AccountBaseline(Long accountId, String currency, BigDecimal amount, boolean manual) {
		this.accountId = accountId;
		this.currency = currency;
		this.amount = amount;
		this.manual = manual;
		this.baselineAt = Instant.now();
	}

	/** 사용자가 기준 자산을 직접 정했다. */
	public void setManually(BigDecimal amount) {
		this.amount = amount;
		this.manual = true;
		this.baselineAt = Instant.now();
	}
}
