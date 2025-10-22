package com.backend.domains.member.service;

import javax.crypto.SecretKey;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backend.common.exception.ApiException;
import com.backend.common.exception.ErrorCode;
import com.backend.common.util.encoder.EncryptUtil;
import com.backend.domains.member.domain.MemberAccount;
import com.backend.domains.member.repository.MemberAccountRepository;
import com.backend.order.domestic.service.DomesticStockService;
import com.backend.order.kis.enums.AccountType;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberAccountService {

	private final MemberAccountRepository memberAccountRepository;
	private final MemberAccountValidationService memberAccountValidationService;

	// 주식 관련
	private final DomesticStockService domesticStockService;

	// 멤버 계좌 저장
	@Transactional
	public long saveMemberAccount(
		final Long memberId,
		final AccountType accountType,
		final String appKey,
		final String secretKey,
		final String accountNumber,
		final String accountProductCode
	) {
		// 중복된 계좌인지 확인
		memberAccountValidationService.validateDuplicateAccount(accountNumber);
		// 실제 존재하는 계좌인지 확인
		domesticStockService.validateAccountExists(appKey, secretKey, accountNumber, accountProductCode, accountType);

		// appKey 암호화
		SecretKey appKeySalt = EncryptUtil.generateKey();
		final String encryptedAppKey = EncryptUtil.encrypt(appKey, appKeySalt);
		// secretKey 암호화
		SecretKey secretKeySalt = EncryptUtil.generateKey();
		final String encryptedSecretKey = EncryptUtil.encrypt(secretKey, secretKeySalt);

		// 멤버 계좌 엔티티 생성 및 저장
		MemberAccount account = MemberAccount.builder()
			.memberId(memberId)
			.accountType(accountType)
			.appKey(encryptedAppKey)
			.appKeySalt(EncryptUtil.keyToString(appKeySalt))
			.secretKey(encryptedSecretKey)
			.secretKeySalt(EncryptUtil.keyToString(secretKeySalt))
			.accountNumber(accountNumber)
			.accountProductCode(accountProductCode)
			.build();
		memberAccountRepository.save(account);
		return account.getId();
	}


	// AppKey 조회(복호화)
	public String getDecryptedAppKey(final Long memberAccountId) {
		MemberAccount account = memberAccountRepository.findById(memberAccountId)
			.orElseThrow(() -> ApiException.from(ErrorCode.MEMBER_ACCOUNT_NOT_FOUND));

		final String appKey = account.getAppKey();
		final String appKeySalt = account.getAppKeySalt();

		return EncryptUtil.decrypt(appKey, EncryptUtil.stringToKey(appKeySalt));
	}

	// SecretKey 조회(복호화)
	public String getDecryptedSecretKey(final Long memberAccountId) {
		MemberAccount account = memberAccountRepository.findById(memberAccountId)
			.orElseThrow(() -> ApiException.from(ErrorCode.MEMBER_ACCOUNT_NOT_FOUND));

		String secretKey = account.getSecretKey();
		String secretKeySalt = account.getSecretKeySalt();

		return EncryptUtil.decrypt(secretKey, EncryptUtil.stringToKey(secretKeySalt));
	}

	//Approval Key 저장
	@Transactional
	public void updateApprovalKey(
		final Long memberAccountId,
		final String approvalKey
	) {
		MemberAccount account = memberAccountRepository.findById(memberAccountId)
			.orElseThrow(() -> ApiException.from(ErrorCode.MEMBER_ACCOUNT_NOT_FOUND));

		account.updateApprovalKey(approvalKey);
		memberAccountRepository.save(account);
	}
}
