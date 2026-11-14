package com.backend.domains.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;

import com.backend.common.crypto.CryptoService;
import com.backend.common.exception.ApiException;
import com.backend.common.exception.ErrorCode;
import com.backend.common.properties.AppProperties;
import com.backend.common.properties.JwtProperties;
import com.backend.common.security.audit.SecurityAudit;
import com.backend.common.security.audit.SecurityEventRepository;
import com.backend.common.security.filter.jwtFilter.JwtTokenProvider;
import com.backend.common.security.guard.AttemptGuard;
import com.backend.domains.auth.dto.AuthDtos.AuthTokens;
import com.backend.domains.auth.dto.AuthDtos.LoginChallenge;
import com.backend.domains.auth.dto.AuthDtos.LoginRequest;
import com.backend.domains.auth.dto.AuthDtos.VerifyLoginRequest;
import com.backend.domains.auth.otp.OtpService;
import com.backend.domains.auth.service.AuthService;
import com.backend.domains.auth.service.SignupPolicy;
import com.backend.domains.email.EmailUtil;
import com.backend.domains.member.domain.Member;
import com.backend.domains.member.domain.RefreshToken;
import com.backend.domains.member.enums.MemberRole;
import com.backend.domains.member.repository.MemberRepository;
import com.backend.domains.member.repository.RefreshTokenRepository;
import com.backend.support.InMemoryKeyValueStore;

class LoginFlowTest {

	private static final String EMAIL = "me@example.com";
	private static final String IP = "5.6.7.8";

	private InMemoryKeyValueStore store;
	private AuthenticationManager authManager;
	private MemberRepository members;
	private RefreshTokenRepository refreshTokens;
	private JwtTokenProvider jwt;
	private EmailUtil mail;
	private AuthService auth;
	private boolean passwordOk;

	@BeforeEach
	void setUp() {
		store = new InMemoryKeyValueStore();
		AppProperties props = new AppProperties();
		props.getSecurity().setCryptoKey(Base64.getEncoder().encodeToString(new byte[32]));
		props.getSecurity().setOtpResendCooldownSeconds(0);
		CryptoService crypto = new CryptoService(props, new JwtProperties(), new MockEnvironment());
		AttemptGuard guard = new AttemptGuard(store, props);
		mail = mock(EmailUtil.class);
		OtpService otp = new OtpService(store, guard, crypto, mail, props);

		authManager = mock(AuthenticationManager.class);
		passwordOk = true;
		when(authManager.authenticate(any())).thenAnswer(inv -> {
			if (!passwordOk) {
				throw new BadCredentialsException("bad");
			}
			return mock(Authentication.class);
		});
		members = mock(MemberRepository.class);
		Member member = Member.builder().email(EMAIL).pw("x").memberRole(MemberRole.USER).build();
		ReflectionTestUtils.setField(member, "id", 1L);
		when(members.findByEmail(EMAIL)).thenReturn(Optional.of(member));
		when(members.findById(1L)).thenReturn(Optional.of(member));

		jwt = mock(JwtTokenProvider.class);
		when(jwt.createAccessToken(1L)).thenReturn("access");
		when(jwt.createRefreshToken()).thenReturn("refresh");
		JwtProperties jwtProps = new JwtProperties();
		JwtProperties.Access access = new JwtProperties.Access();
		access.setExpiration(3600);
		jwtProps.setAccess(access);
		JwtProperties.Refresh refresh = new JwtProperties.Refresh();
		refresh.setExpiration(1_209_600);
		jwtProps.setRefresh(refresh);

		refreshTokens = mock(RefreshTokenRepository.class);
		SecurityEventRepository events = mock(SecurityEventRepository.class);
		SecurityAudit audit = mock(SecurityAudit.class);

		auth = new AuthService(members, null, authManager, jwt, jwtProps, refreshTokens, null, null, props,
			mock(SignupPolicy.class), null, null, null, null, otp, guard, store, audit, events, mail, null);
	}

	private String sentCode() {
		ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
		verify(mail, org.mockito.Mockito.atLeastOnce()).sendEmail(eq(EMAIL), anyString(), text.capture());
		Matcher m = Pattern.compile("(\\d{6})").matcher(text.getValue());
		assertThat(m.find()).isTrue();
		return m.group(1);
	}

	private static ErrorCode codeOf(Throwable t) {
		return ((ApiException)t).getErrorCode();
	}

	@Test
	void passwordThenEmailCodeIssuesTokens() {
		LoginChallenge c = auth.login(new LoginRequest(EMAIL, "pw"), IP);
		assertThat(c.maskedEmail()).isEqualTo("m***e@example.com");
		AuthTokens tokens = auth.verifyLogin(new VerifyLoginRequest(c.challengeId(), sentCode()), IP);
		assertThat(tokens.accessToken()).isEqualTo("access");
		assertThat(tokens.refreshToken()).isEqualTo("refresh");
		// 리프레시 토큰은 해시로만 저장된다.
		ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
		verify(refreshTokens).save(saved.capture());
		assertThat(saved.getValue().getTokenHash()).isNotEqualTo("refresh").hasSize(64);
	}

	@Test
	void passwordFailureDoesNotSendMailAndNeverIssuesTokens() {
		passwordOk = false;
		assertThatThrownBy(() -> auth.login(new LoginRequest(EMAIL, "bad"), IP))
			.satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.INVALID_CREDENTIALS));
		verify(mail, never()).sendEmail(anyString(), anyString(), anyString());
	}

	@Test
	void passwordAndOtpFailuresCountTogetherAndLockAtTen() {
		passwordOk = false;
		for (int i = 0; i < 5; i++) {
			assertThatThrownBy(() -> auth.login(new LoginRequest(EMAIL, "bad"), IP))
				.satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.INVALID_CREDENTIALS));
		}
		passwordOk = true;
		LoginChallenge c = auth.login(new LoginRequest(EMAIL, "pw"), IP);
		for (int i = 0; i < 4; i++) {
			assertThatThrownBy(() -> auth.verifyLogin(new VerifyLoginRequest(c.challengeId(), "000000"), IP))
				.satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.INVALID_OTP));
		}
		// 합쳐서 9회째까지 실패. 10번째 실패에서 잠긴다.
		assertThatThrownBy(() -> auth.verifyLogin(new VerifyLoginRequest(c.challengeId(), "000000"), IP))
			.satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.INVALID_OTP));
		assertThatThrownBy(() -> auth.login(new LoginRequest(EMAIL, "pw"), IP))
			.satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.TOO_MANY_ATTEMPTS));
		// 잠긴 동안에는 맞는 인증번호도 받아주지 않는다.
		assertThatThrownBy(() -> auth.verifyLogin(new VerifyLoginRequest(c.challengeId(), sentCode()), IP))
			.satisfies(t -> assertThat(codeOf(t)).isIn(ErrorCode.TOO_MANY_ATTEMPTS, ErrorCode.INVALID_OTP));

		// 잠금 시간이 지나면 다시 로그인할 수 있다.
		store.advance(Duration.ofMinutes(31));
		LoginChallenge again = auth.login(new LoginRequest(EMAIL, "pw"), IP);
		assertThat(auth.verifyLogin(new VerifyLoginRequest(again.challengeId(), sentCode()), IP).accessToken()).isEqualTo("access");
	}

	@Test
	void successfulLoginResetsTheFailureCount() {
		passwordOk = false;
		for (int i = 0; i < 9; i++) {
			assertThatThrownBy(() -> auth.login(new LoginRequest(EMAIL, "bad"), IP)).isInstanceOf(ApiException.class);
		}
		passwordOk = true;
		LoginChallenge c = auth.login(new LoginRequest(EMAIL, "pw"), IP);
		auth.verifyLogin(new VerifyLoginRequest(c.challengeId(), sentCode()), IP);

		// 초기화되었으므로 다시 9번 틀려도 잠기지 않는다.
		passwordOk = false;
		store.advance(Duration.ofSeconds(1));
		for (int i = 0; i < 9; i++) {
			assertThatThrownBy(() -> auth.login(new LoginRequest(EMAIL, "bad"), IP))
				.satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.INVALID_CREDENTIALS));
		}
	}

	@Test
	void unknownChallengeIsRejected() {
		assertThatThrownBy(() -> auth.verifyLogin(new VerifyLoginRequest("nope", "123456"), IP))
			.satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.INVALID_OTP));
	}
}
