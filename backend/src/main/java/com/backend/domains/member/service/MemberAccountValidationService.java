package com.backend.domains.member.service;

import static com.backend.common.exception.ErrorCode.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backend.common.exception.ApiException;
import com.backend.domains.member.repository.MemberAccountRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberAccountValidationService {

	private final MemberAccountRepository memberAccountRepository;

	// 계좌가 해당 멤버의 계좌인지 검증(권한 없으면 예외)
	public void validateMemberAccount(Long memberId, Long memberAccountId) throws ApiException {
		boolean exists = memberAccountRepository.existsByIdAndMemberId(memberId, memberAccountId);
		if (!exists) {
			throw ApiException.from(FORBIDDEN);
		}
	}

	// 중복된 계좌이면 예외
	public void validateDuplicateAccount(String accountNumber) throws ApiException {
		boolean exists = memberAccountRepository.existsByAccountNumber(accountNumber);
		if (exists) {
			throw ApiException.from(DUPLICATE_MEMBER_ACCOUNT);
		}
	}
}
