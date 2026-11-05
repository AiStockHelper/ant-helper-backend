package com.backend.domains.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;

import com.backend.common.crypto.CryptoService;
import com.backend.common.exception.ApiException;
import com.backend.common.exception.ErrorCode;
import com.backend.common.properties.AppProperties;
import com.backend.common.properties.JwtProperties;
import com.backend.common.security.guard.AttemptGuard;
import com.backend.domains.auth.otp.OtpService;
import com.backend.domains.auth.otp.OtpService.Issued;
import com.backend.domains.auth.otp.OtpService.Purpose;
import com.backend.domains.email.EmailUtil;
import com.backend.support.InMemoryKeyValueStore;

class OtpServiceTest {

	private static final String EMAIL = "me@example.com";
	private static final String IP = "1.2.3.4";

	private InMemoryKeyValueStore store;
	private EmailUtil mail;
	private AppProperties props;
	private OtpService otp;

	@BeforeEach
	void setUp() {
		store = new InMemoryKeyValueStore();
		mail = mock(EmailUtil.class);
		props = new AppProperties();
		props.getSecurity().setCryptoKey(Base64.getEncoder().encodeToString(new byte[32]));
		props.getSecurity().setOtpResendCooldownSeconds(0);
		CryptoService crypto = new CryptoService(props, new JwtProperties(), new MockEnvironment());
		otp = new OtpService(store, new AttemptGuard(store, props), crypto, mail, props);
	}

	private String lastCode() {
		ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
		verify(mail, org.mockito.Mockito.atLeastOnce()).sendEmail(eq(EMAIL), anyString(), text.capture());
		Matcher m = Pattern.compile("(\\d{6})").matcher(text.getValue());
		assertThat(m.find()).isTrue();
		return m.group(1);
	}

	@Test
	void correctCodeVerifiesOnceAndReturnsSubject() {
		Issued issued = otp.issue(Purpose.LOGIN, EMAIL, EMAIL, IP);
		String code = lastCode();
		assertThat(otp.verify(Purpose.LOGIN, issued.challengeId(), code)).isEqualTo(EMAIL);
		assertThatThrownBy(() -> otp.verify(Purpose.LOGIN, issued.challengeId(), code))
			.isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_OTP));
	}

	@Test
	void codeIsNotStoredInPlainText() {
		Issued issued = otp.issue(Purpose.LOGIN, EMAIL, EMAIL, IP);
		String code = lastCode();
		String stored = store.get("otp:LOGIN:" + issued.challengeId()).orElseThrow();
		assertThat(stored).doesNotContain(code);
	}

	@Test
	void wrongCodeIsRejectedAndChallengeDiesAfterFiveMisses() {
		Issued issued = otp.issue(Purpose.LOGIN, EMAIL, EMAIL, IP);
		String code = lastCode();
		String wrong = code.equals("000000") ? "111111" : "000000";
		for (int i = 0; i < 5; i++) {
			assertThatThrownBy(() -> otp.verify(Purpose.LOGIN, issued.challengeId(), wrong))
				.isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_OTP));
		}
		// 여섯 번째에는 맞는 번호를 넣어도 이미 폐기되어 있다.
		assertThatThrownBy(() -> otp.verify(Purpose.LOGIN, issued.challengeId(), code))
			.isInstanceOf(ApiException.class);
	}

	@Test
	void codeFromAnotherPurposeIsNotAccepted() {
		Issued issued = otp.issue(Purpose.LOGIN, EMAIL, EMAIL, IP);
		String code = lastCode();
		assertThatThrownBy(() -> otp.verify(Purpose.STEP_UP, issued.challengeId(), code)).isInstanceOf(ApiException.class);
	}

	@Test
	void expiredChallengeIsRejected() {
		Issued issued = otp.issue(Purpose.LOGIN, EMAIL, EMAIL, IP);
		String code = lastCode();
		store.advance(Duration.ofMinutes(6));
		assertThatThrownBy(() -> otp.verify(Purpose.LOGIN, issued.challengeId(), code)).isInstanceOf(ApiException.class);
	}

	@Test
	void malformedCodesAreRejected() {
		Issued issued = otp.issue(Purpose.LOGIN, EMAIL, EMAIL, IP);
		for (String bad : new String[] {null, "", "12345", "1234567", "abcdef"}) {
			assertThatThrownBy(() -> otp.verify(Purpose.LOGIN, issued.challengeId(), bad)).isInstanceOf(ApiException.class);
		}
	}

	@Test
	void eleventhConsecutiveMailIsBlockedAndSuccessResetsTheCount() {
		for (int i = 0; i < 10; i++) {
			otp.issue(Purpose.LOGIN, EMAIL, EMAIL, "9.9.9." + i);
		}
		assertThatThrownBy(() -> otp.issue(Purpose.LOGIN, EMAIL, EMAIL, "9.9.9.99"))
			.isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TOO_MANY_ATTEMPTS));

		otp.clearSendCounters(EMAIL, IP);
		store.advance(Duration.ofMinutes(31));
		otp.issue(Purpose.LOGIN, EMAIL, EMAIL, IP);
	}

	@Test
	void resendCooldownIsEnforced() {
		props.getSecurity().setOtpResendCooldownSeconds(30);
		otp.issue(Purpose.LOGIN, EMAIL, EMAIL, IP);
		assertThatThrownBy(() -> otp.issue(Purpose.LOGIN, EMAIL, EMAIL, IP))
			.isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TOO_MANY_ATTEMPTS));
	}

	@Test
	void peekSubjectDoesNotConsumeTheChallenge() {
		Issued issued = otp.issue(Purpose.LOGIN, EMAIL, EMAIL, IP);
		assertThat(otp.peekSubject(Purpose.LOGIN, issued.challengeId())).contains(EMAIL);
		assertThat(otp.peekSubject(Purpose.LOGIN, issued.challengeId())).contains(EMAIL);
		assertThat(otp.peekSubject(Purpose.LOGIN, "nope")).isEmpty();
	}
}
