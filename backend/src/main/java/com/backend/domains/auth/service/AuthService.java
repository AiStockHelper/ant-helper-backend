package com.backend.domains.auth.service;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backend.common.exception.ApiException;
import com.backend.common.exception.ErrorCode;
import com.backend.common.properties.AppProperties;
import com.backend.common.properties.JwtProperties;
import com.backend.common.security.filter.jwtFilter.JwtTokenProvider;
import com.backend.common.util.encoder.PasswordEncoderUtil;
import com.backend.domains.auth.dto.AuthDtos.AuthTokens;
import com.backend.domains.auth.dto.AuthDtos.LoginRequest;
import com.backend.domains.auth.dto.AuthDtos.Me;
import com.backend.domains.auth.dto.AuthDtos.SignupRequest;
import com.backend.domains.email.service.EmailTokenService;
import com.backend.domains.member.domain.Member;
import com.backend.domains.member.domain.RefreshToken;
import com.backend.domains.member.enums.MemberRole;
import com.backend.domains.member.repository.MemberRepository;
import com.backend.domains.member.repository.RefreshTokenRepository;
import com.backend.domains.member.service.MemberService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AuthService {

	private final MemberRepository memberRepository;
	private final PasswordEncoderUtil passwordEncoderUtil;
	private final AuthenticationManager authenticationManager;
	private final JwtTokenProvider jwtTokenProvider;
	private final JwtProperties jwtProperties;
	private final RefreshTokenRepository refreshTokenRepository;
	private final EmailTokenService emailTokenService;
	private final MemberService memberService;
	private final AppProperties appProperties;
	private final com.backend.domains.chat.ChatService chatService;
	private final com.backend.domains.schedule.ScheduledTaskRepository scheduledTasks;
	private final com.backend.domains.member.repository.MemberAccountRepository accountRepository;
	private final com.backend.market.KisGateway kisGateway;

	@Transactional
	public Me signup(SignupRequest request) {
		final String email = request.email().trim().toLowerCase();
		if (memberRepository.existsByEmail(email)) {
			throw ApiException.from(ErrorCode.EMAIL_DUPLICATE);
		}
		if (appProperties.getAuth().isRequireEmailVerification()) {
			if (request.emailToken() == null || request.emailToken().isBlank()) {
				throw ApiException.from(ErrorCode.UNAUTHENTICATED_EMAIL);
			}
			emailTokenService.verifyEmailToken(request.emailToken());
		}

		Member member = Member.builder()
			.email(email)
			.pw(passwordEncoderUtil.encodePassword(request.password()))
			.memberRole(MemberRole.USER)
			.nickname(request.nickname() == null || request.nickname().isBlank() ? null : request.nickname().trim())
			.build();
		return Me.from(memberRepository.save(member));
	}

	public AuthTokens login(LoginRequest request) {
		final String email = request.email().trim().toLowerCase();
		try {
			authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(email, request.password()));
		} catch (AuthenticationException e) {
			throw ApiException.from(ErrorCode.INVALID_CREDENTIALS);
		}
		Member member = memberRepository.findByEmail(email)
			.orElseThrow(() -> ApiException.from(ErrorCode.INVALID_CREDENTIALS));
		return issueTokens(member);
	}

	public AuthTokens refresh(String refreshToken) {
		if (!jwtTokenProvider.isTokenValid(refreshToken)) {
			throw ApiException.from(ErrorCode.INVALID_REFRESH_TOKEN);
		}
		RefreshToken stored = refreshTokenRepository.findById(refreshToken)
			.orElseThrow(() -> ApiException.from(ErrorCode.INVALID_REFRESH_TOKEN));
		Member member = memberRepository.findById(stored.getMemberId())
			.orElseThrow(() -> ApiException.from(ErrorCode.INVALID_REFRESH_TOKEN));
		refreshTokenRepository.deleteById(refreshToken);
		return issueTokens(member);
	}

	public void logout(Long memberId, String accessToken) {
		memberService.logoutMember(memberId, accessToken);
	}

	/** 회원 탈퇴: 계좌 키, 대화, 예약 등 이 회원의 모든 데이터를 함께 지운다. */
	@Transactional
	public void deleteMember(Long memberId) {
		chatService.deleteAll(memberId);
		scheduledTasks.deleteAllByMemberId(memberId);
		accountRepository.findAllByMemberId(memberId).forEach(a -> kisGateway.evict(a.getId()));
		accountRepository.deleteAllByMemberId(memberId);
		refreshTokenRepository.deleteByMemberId(memberId);
		memberRepository.deleteById(memberId);
	}

	@Transactional(readOnly = true)
	public Me me(Long memberId) {
		return Me.from(find(memberId));
	}

	@Transactional
	public Me updateNickname(Long memberId, String nickname) {
		Member member = find(memberId);
		member.updateNickname(nickname.trim());
		return Me.from(member);
	}

	private Member find(Long memberId) {
		return memberRepository.findById(memberId).orElseThrow(() -> ApiException.from(ErrorCode.MEMBER_NOT_FOUND));
	}

	private AuthTokens issueTokens(Member member) {
		String access = jwtTokenProvider.createAccessToken(member.getId());
		String refresh = jwtTokenProvider.createRefreshToken();
		refreshTokenRepository.deleteByMemberId(member.getId());
		refreshTokenRepository.save(new RefreshToken(refresh, member.getId()));
		return new AuthTokens(access, refresh, jwtProperties.getAccess().getExpiration(), Me.from(member));
	}
}
