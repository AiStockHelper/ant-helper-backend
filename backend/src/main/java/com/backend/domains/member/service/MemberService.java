package com.backend.domains.member.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backend.common.exception.ApiException;
import com.backend.common.exception.ErrorCode;
import com.backend.domains.member.domain.LogoutToken;
import com.backend.domains.member.repository.LogoutRepository;
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
	private final LogoutRepository logoutRepository;

	// 이메일 중복 조회
	public void validateDuplicatedEmail(String email) {
		boolean isDuplicated = memberRepository.existsByEmail(email);
		if (isDuplicated) {
			throw ApiException.from(ErrorCode.EMAIL_DUPLICATE);
		}
	}

	//로그아웃
	@Transactional
	public void logoutMember(final Long memberId, String accessToken) {
		// 회원의 refreshToken 삭제
		refreshTokenRepository.deleteByMemberId(memberId);

		// 같은 accessToken으로 다시 로그인하지 못하도록 블랙리스트에 저장
		logoutRepository.save(new LogoutToken(UUID.randomUUID().toString(), accessToken));
	}
}
