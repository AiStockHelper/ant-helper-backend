package com.backend.domains.member.domain;

import com.backend.order.kis.enums.AccountType;

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

	@Builder
	private MemberAccount(
		Long memberId,
		AccountType accountType,
		String appKey,
		String secretKey,
		String appKeySalt,
		String secretKeySalt,
		String accountNumber,
		String accountProductCode
	) {
		this.memberId = memberId;
		this.accountType = accountType;
		this.appKey = appKey;
		this.secretKey = secretKey;
		this.appKeySalt = appKeySalt;
		this.secretKeySalt = secretKeySalt;
		this.accountNumber = accountNumber;
		this.accountProductCode = accountProductCode;
	}

	// approvalKey 업데이트 메서드
	public void updateApprovalKey(String approvalKey) {
		this.approvalKey = approvalKey;
	}
}
