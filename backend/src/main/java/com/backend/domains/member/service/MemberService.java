package com.backend.domains.member.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backend.common.exception.ApiException;
import com.backend.common.exception.ErrorCode;
import com.backend.common.util.encoder.PasswordEncoderUtil;
import com.backend.domains.email.service.EmailTokenService;
import com.backend.domains.member.domain.LogoutToken;
import com.backend.domains.member.domain.Member;
import com.backend.domains.member.dto.request.CreateMemberRequest;
import com.backend.domains.member.enums.AutoTradeState;
import com.backend.domains.member.enums.MemberRole;
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
	private final MemberAccountService memberAccountService;

	// 비밀번호 암호화 유틸
	private final PasswordEncoderUtil passwordEncoderUtil;

	// 회원 인증 관련
	private final EmailTokenService emailTokenService;
	private final RefreshTokenRepository refreshTokenRepository;
	private final LogoutRepository logoutRepository;

	// 회원가입하기
	@Transactional(rollbackFor = ApiException.class)
	public void createMember(CreateMemberRequest request) {
		// 이메일 검증
		validateDuplicatedEmail(request.getEmail());
		emailTokenService.verifyEmailToken(request.getToken());

		// 비밀번호 암호화
		String encodedPassword = passwordEncoderUtil.encodePassword(request.getPw());

		// 회원 저장
		Member member = Member.builder()
			.email(request.getEmail())
			.pw(encodedPassword)
			.memberRole(MemberRole.USER) // 기본 권한은 USER
			.autoTradeState(AutoTradeState.OFF)
			.build();
		memberRepository.save(member);
	}

	// 이메일 중복 조회
	public void validateDuplicatedEmail(String email) {
		boolean isDuplicated = memberRepository.existsByEmail(email);
		if (isDuplicated) {
			throw ApiException.from(ErrorCode.EMAIL_DUPLICATE);
		}
	}

	// 회원 삭제하기
	@Transactional
	public void deleteMember(Member member) {
		memberRepository.delete(member);
	}

	//로그아웃
	@Transactional
	public void logoutMember(Member member, String accessToken) {
		Long memberId = member.getId();

		// 회원의 refreshToken 삭제
		refreshTokenRepository.deleteByMemberId(memberId);

		// 같은 accessToken으로 다시 로그인하지 못하도록 블랙리스트에 저장
		logoutRepository.save(new LogoutToken(UUID.randomUUID().toString(), accessToken));
	}

	// 자동 거래 상태 변경
	@Transactional
	public void updateAutoTradeState(Member member, AutoTradeState autoTradeState) {
		member.setAutoTradeState(autoTradeState);
	}

	// 자동 거래를 ON한 모든 멤버 조회
	public List<Member> findAllAutoTradeOnMember() {
		return memberRepository.findAllByAutoTradeState(AutoTradeState.ON);
	}

	// 자동 거래 상태 확인
	public boolean isAutoTradeStateOn(Member member) {
		return member.getAutoTradeState() == AutoTradeState.ON;
	}
}
