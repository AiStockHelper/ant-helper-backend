package com.backend.domains.member.domain;

import com.backend.common.util.encoder.EncryptUtil;
import com.backend.order.kis.enums.AccountType;

import java.time.LocalDateTime;

import jakarta.persistence.PrePersist;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 한국투자증권 계좌 정보
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberAccount {

	@Id
	@Column(name = "id")
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	// 회원 고유 번호(외래키)
	@Column(name = "member_id", nullable = false)
	private Long memberId;

	@Column(name = "account_type", nullable = false)
	@Enumerated(EnumType.STRING)
	private AccountType accountType;

	// 한국 투자 증권 appkey
	@Setter
	@Column(name = "app_key", length = 1000, nullable = false)
	private String appKey;

	// appkey 암호화에 사용된 salt 값
	@Column(name = "app_key_salt", nullable = false)
	private String appKeySalt;

	// 한국 투자 증권 appsecret
	@Setter
	@Column(name = "secret_key", nullable = false, length = 1000)
	private String secretKey;

	// secretkey 암호화에 사용된 salt 값
	@Column(name = "secret_key_salt", nullable = false)
	private String secretKeySalt;

	// 한국 투자 증권 웹소켓 키(서비스에서 주기적 발급)
	@Setter
	@Column(name = "approval_key")
	private String approvalKey;

	// 한국 투자 증권 종합 계좌 번호
	@Column(name = "account_number", nullable = false)
	private String accountNumber;

	// 한국 투자 증권 계좌 상품 코드 (계좌 번호 뒷 2자리)
	@Column(name = "account_product_code", nullable = false)
	private String accountProductCode;

	// 사용자가 붙인 계좌 별칭
	@Column(name = "alias", length = 50)
	private String alias;

	// 채팅/조회에서 기본으로 사용할 계좌
	@Column(name = "is_default")
	private Boolean isDefault = false;

	@Column(name = "created_at")
	private LocalDateTime createdAt;

	@Builder
	private MemberAccount(
		Long memberId,
		AccountType accountType,
		String appKey,
		String secretKey,
		String appKeySalt,
		String secretKeySalt,
		String accountNumber,
		String accountProductCode,
		String alias,
		Boolean isDefault
	) {
		this.memberId = memberId;
		this.accountType = accountType;
		this.appKey = appKey;
		this.secretKey = secretKey;
		this.appKeySalt = appKeySalt;
		this.secretKeySalt = secretKeySalt;
		this.accountNumber = accountNumber;
		this.accountProductCode = accountProductCode;
		this.alias = alias;
		this.isDefault = isDefault != null && isDefault;
	}

	@PrePersist
	void onCreate() {
		if (createdAt == null) {
			createdAt = LocalDateTime.now();
		}
		if (isDefault == null) {
			isDefault = false;
		}
	}

	public void rename(String alias) {
		this.alias = alias == null || alias.isBlank() ? null : alias.trim();
	}

	public void markDefault(boolean value) {
		this.isDefault = value;
	}

	public boolean isDefaultAccount() {
		return Boolean.TRUE.equals(isDefault);
	}

	public boolean isPaper() {
		return accountType == AccountType.PAPER_TRADE;
	}

	/** 사용자에게 보여줄 이름: 별칭이 없으면 유형 기반 이름 */
	public String displayName() {
		if (alias != null && !alias.isBlank()) {
			return alias;
		}
		return isPaper() ? "모의 계좌" : "실전 계좌";
	}

	public String maskedAccountNumber() {
		String n = accountNumber == null ? "" : accountNumber;
		String masked = n.length() <= 4 ? n : n.substring(0, 2) + "****" + n.substring(n.length() - 2);
		return masked + "-" + accountProductCode;
	}

	// approvalKey 업데이트 메서드
	public void updateApprovalKey(String approvalKey) {
		this.approvalKey = approvalKey;
	}

	public String getDecryptedAppKey() {
		return EncryptUtil.decrypt(appKey, EncryptUtil.stringToKey(appKeySalt));
	}

	public String getDecryptedSecretKey() {
		return EncryptUtil.decrypt(secretKey, EncryptUtil.stringToKey(secretKeySalt));
	}
}
