package com.backend.domains.auth.service;

import java.time.Duration;
import java.util.Optional;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backend.common.crypto.CryptoService;
import com.backend.common.exception.ApiException;
import com.backend.common.exception.ErrorCode;
import com.backend.common.properties.AppProperties;
import com.backend.common.properties.JwtProperties;
import com.backend.common.security.audit.SecurityAudit;
import com.backend.common.security.audit.SecurityEventRepository;
import com.backend.common.security.filter.jwtFilter.JwtTokenProvider;
import com.backend.common.security.guard.AttemptGuard;
import com.backend.common.security.guard.KeyValueStore;
import com.backend.common.util.encoder.PasswordEncoderUtil;
import com.backend.domains.auth.dto.AuthDtos.AuthTokens;
import com.backend.domains.auth.dto.AuthDtos.LoginChallenge;
import com.backend.domains.auth.dto.AuthDtos.LoginRequest;
import com.backend.domains.auth.dto.AuthDtos.Me;
import com.backend.domains.auth.dto.AuthDtos.SignupRequest;
import com.backend.domains.auth.dto.AuthDtos.VerifyLoginRequest;
import com.backend.domains.auth.otp.OtpService;
import com.backend.domains.auth.otp.OtpService.Issued;
import com.backend.domains.auth.otp.OtpService.Purpose;
import com.backend.domains.email.EmailUtil;
import com.backend.domains.email.service.EmailTokenService;
import com.backend.domains.member.domain.Member;
import com.backend.domains.member.domain.RefreshToken;
import com.backend.domains.member.enums.MemberRole;
import com.backend.domains.member.repository.MemberRepository;
import com.backend.domains.member.repository.RefreshTokenRepository;
import com.backend.domains.member.service.MemberService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

	/** 방금 쓴 리프레시 토큰이 이 시간 안에 다시 오면 동시 요청으로 보고 세션을 폐기하지는 않는다. */
	private static final long REUSE_GRACE_MILLIS = 10_000L;

	private final MemberRepository memberRepository;
	private final PasswordEncoderUtil passwordEncoderUtil;
	private final AuthenticationManager authenticationManager;
	private final JwtTokenProvider jwtTokenProvider;
	private final JwtProperties jwtProperties;
	private final RefreshTokenRepository refreshTokenRepository;
	private final EmailTokenService emailTokenService;
	private final MemberService memberService;
	private final AppProperties appProperties;
	private final SignupPolicy signupPolicy;
	private final com.backend.domains.chat.ChatService chatService;
	private final com.backend.domains.agent.AgentTaskService agentTasks;
	private final com.backend.domains.member.repository.MemberAccountRepository accountRepository;
	private final com.backend.market.KisGateway kisGateway;
	private final OtpService otp;
	private final AttemptGuard guard;
	private final KeyValueStore store;
	private final SecurityAudit audit;
	private final SecurityEventRepository securityEvents;
	private final EmailUtil mail;
	private final com.backend.market.queue.KisOrderRequestRepository orderRequests;

	@Transactional
	public Me signup(SignupRequest request) {
		final String email = request.email().trim().toLowerCase();
		signupPolicy.requireAllowed(email);
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

	// ─────────────────────────── 로그인: 비밀번호 → 이메일 인증번호 ───────────────────────────

	/** 1단계: 비밀번호를 확인하고 이메일로 인증번호를 보낸다. 토큰은 아직 발급하지 않는다. */
	public LoginChallenge login(LoginRequest request, String ip) {
		final String email = request.email().trim().toLowerCase();
		guard.assertOpen(emailKey(email));
		guard.assertOpen(ipKey(ip));
		try {
			authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(email, request.password()));
		} catch (AuthenticationException e) {
			loginFailed(SecurityAudit.LOGIN_PASSWORD_FAILED, email, ip);
			throw ApiException.from(ErrorCode.INVALID_CREDENTIALS);
		}
		memberRepository.findByEmail(email).orElseThrow(() -> ApiException.from(ErrorCode.INVALID_CREDENTIALS));
		Issued issued = otp.issue(Purpose.LOGIN, email, email, ip);
		return new LoginChallenge(issued.challengeId(), maskEmail(email), issued.expiresInSeconds(), issued.resendAfterSeconds());
	}

	/** 2단계: 인증번호가 맞으면 토큰을 발급한다. */
	public AuthTokens verifyLogin(VerifyLoginRequest request, String ip) {
		String email = otp.peekSubject(Purpose.LOGIN, request.challengeId())
			.orElseThrow(() -> ApiException.from(ErrorCode.INVALID_OTP));
		guard.assertOpen(emailKey(email));
		guard.assertOpen(ipKey(ip));
		try {
			otp.verify(Purpose.LOGIN, request.challengeId(), request.code());
		} catch (ApiException e) {
			if (e.getErrorCode() == ErrorCode.INVALID_OTP) {
				loginFailed(SecurityAudit.LOGIN_OTP_FAILED, email, ip);
			}
			throw e;
		}
		Member member = memberRepository.findByEmail(email)
			.orElseThrow(() -> ApiException.from(ErrorCode.INVALID_CREDENTIALS));
		guard.reset(emailKey(email));
		guard.reset(ipKey(ip));
		otp.clearSendCounters(email, ip);

		boolean firstEver = securityEvents.findTop50ByMemberIdOrderByIdDesc(member.getId()).isEmpty();
		boolean newIp = !securityEvents.existsByMemberIdAndTypeAndIp(member.getId(), SecurityAudit.LOGIN_SUCCESS, ip);
		audit.record(SecurityAudit.LOGIN_SUCCESS, member.getId(), email, ip, null);
		if (newIp && !firstEver) {
			mail.sendEmail(email, "[Ant Helper] 새로운 위치에서 로그인했습니다",
				"방금 새로운 IP(" + ip + ")에서 로그인되었습니다.\n본인이 아니라면 즉시 비밀번호를 바꾸고, 앱의 '자동 실행' 화면에서 전체 일시정지를 눌러 주세요.");
		}
		return issueTokens(member);
	}

	private void loginFailed(String type, String email, String ip) {
		audit.record(type, null, email, ip, null);
		AppProperties.Security sec = appProperties.getSecurity();
		boolean locked = guard.fail(emailKey(email), sec.getMaxLoginFailures());
		guard.fail(ipKey(ip), sec.getMaxFailuresPerIp());
		if (locked) {
			audit.record(SecurityAudit.LOGIN_LOCKED, null, email, ip, "연속 실패 " + sec.getMaxLoginFailures() + "회");
		}
	}

	private static String emailKey(String email) {
		return "login:email:" + email;
	}

	private static String ipKey(String ip) {
		return "login:ip:" + ip;
	}

	static String maskEmail(String email) {
		int at = email.indexOf('@');
		if (at < 2) {
			return email;
		}
		return email.charAt(0) + "***" + email.substring(at - 1);
	}

	// ─────────────────────────── 토큰 ───────────────────────────

	/** 리프레시 토큰은 한 번만 쓸 수 있다. 쓸 때마다 새 토큰으로 교체되고, 이미 쓴 토큰이 다시 오면 세션 전체를 폐기한다. */
	public AuthTokens refresh(String refreshToken, String ip) {
		if (!jwtTokenProvider.isValidRefreshToken(refreshToken)) {
			throw ApiException.from(ErrorCode.INVALID_REFRESH_TOKEN);
		}
		String hash = CryptoService.sha256Hex(refreshToken);
		Optional<RefreshToken> stored = refreshTokenRepository.findById(hash);
		if (stored.isEmpty()) {
			store.get(usedKey(hash)).ifPresent(v -> handleReuse(v, ip));
			throw ApiException.from(ErrorCode.INVALID_REFRESH_TOKEN);
		}
		Member member = memberRepository.findById(stored.get().getMemberId())
			.orElseThrow(() -> ApiException.from(ErrorCode.INVALID_REFRESH_TOKEN));
		refreshTokenRepository.deleteById(hash);
		store.put(usedKey(hash), member.getId() + "|" + System.currentTimeMillis(),
			Duration.ofSeconds(jwtProperties.refreshSeconds()));
		return issueTokens(member);
	}

	private void handleReuse(String marker, String ip) {
		String[] parts = marker.split("\\|");
		long memberId = Long.parseLong(parts[0]);
		long usedAt = parts.length > 1 ? Long.parseLong(parts[1]) : 0L;
		if (System.currentTimeMillis() - usedAt < REUSE_GRACE_MILLIS) {
			return;
		}
		refreshTokenRepository.deleteByMemberId(memberId);
		audit.record(SecurityAudit.REFRESH_REUSE, memberId, null, ip, "이미 사용된 리프레시 토큰이 다시 제출되어 세션을 폐기했습니다.");
	}

	private static String usedKey(String hash) {
		return "rt:used:" + hash;
	}

	public void logout(Long memberId, String accessToken, String ip) {
		jwtTokenProvider.revoke(accessToken);
		memberService.logoutMember(memberId);
		audit.record(SecurityAudit.LOGOUT, memberId, null, ip, null);
	}

	/** 회원 탈퇴: 계좌 키, 대화, 예약 등 이 회원의 모든 데이터를 함께 지운다. */
	@Transactional
	public void deleteMember(Long memberId) {
		chatService.deleteAll(memberId);
		agentTasks.deleteAllForMember(memberId);
		accountRepository.findAllByMemberId(memberId).forEach(a -> kisGateway.evict(a.getId()));
		orderRequests.deleteAllByMemberId(memberId);
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
		refreshTokenRepository.save(new RefreshToken(CryptoService.sha256Hex(refresh), member.getId(),
			(long)jwtProperties.refreshSeconds()));
		return new AuthTokens(access, refresh, jwtProperties.getAccess().getExpiration(), Me.from(member));
	}
}
