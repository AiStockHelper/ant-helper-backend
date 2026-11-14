package com.backend.domains.member.service;


import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backend.common.exception.ApiException;
import com.backend.common.exception.ErrorCode;
import com.backend.domains.member.repository.MemberRepository;
import com.backend.domains.member.repository.RefreshTokenRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberService {

	// 회원 관련
	private final MemberRepository memberRepository;

	// 회원 인증 관련
	private final RefreshTokenRepository refreshTokenRepository;

	// 이메일 중복 조회
	public void validateDuplicatedEmail(String email) {
		boolean isDuplicated = memberRepository.existsByEmail(email);
		if (isDuplicated) {
			throw ApiException.from(ErrorCode.EMAIL_DUPLICATE);
		}
	}

	/** 로그아웃: 이 회원의 리프레시 토큰을 모두 지운다(액세스 토큰 폐기는 호출한 쪽이 한다). */
	@Transactional
	public void logoutMember(final Long memberId) {
		refreshTokenRepository.deleteByMemberId(memberId);
	}
}
