package com.backend.domains.auth.otp;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.backend.common.crypto.CryptoService;
import com.backend.common.exception.ApiException;
import com.backend.common.exception.ErrorCode;
import com.backend.common.properties.AppProperties;
import com.backend.common.security.guard.AttemptGuard;
import com.backend.common.security.guard.KeyValueStore;
import com.backend.domains.email.EmailUtil;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 이메일로 보내는 6자리 일회용 인증번호.
 * 번호 자체는 저장하지 않고(키 기반 해시만 저장), 틀리면 횟수를 세어 한도를 넘으면 그 번호를 폐기한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OtpService {

	public enum Purpose {
		LOGIN("로그인"), STEP_UP("실계좌 보호 확인");

		private final String label;

		Purpose(String label) {
			this.label = label;
		}
	}

	public record Issued(String challengeId, int expiresInSeconds, int resendAfterSeconds) {
	}

	private static final SecureRandom RANDOM = new SecureRandom();

	private final KeyValueStore store;
	private final AttemptGuard guard;
	private final CryptoService crypto;
	private final EmailUtil mail;
	private final AppProperties props;

	/** subject 는 이 번호가 증명하려는 대상(로그인이면 이메일, 추가 확인이면 회원 ID). */
	public Issued issue(Purpose purpose, String subject, String email, String ip) {
		AppProperties.Security sec = props.getSecurity();
		String sendKey = "otp-send:" + email;
		String ipKey = "otp-send-ip:" + ip;
		guard.assertOpen(sendKey);
		guard.assertOpen(ipKey);
		String coolKey = "otp:cool:" + purpose + ":" + email;
		if (store.get(coolKey).isPresent()) {
			throw ApiException.of(ErrorCode.TOO_MANY_ATTEMPTS,
				Math.max(1, store.ttlSeconds(coolKey)) + "초 뒤에 다시 요청해 주세요.");
		}
		guard.fail(sendKey, sec.getMaxOtpSends());
		guard.fail(ipKey, sec.getMaxFailuresPerIp());

		String code = String.format("%06d", RANDOM.nextInt(1_000_000));
		byte[] raw = new byte[24];
		RANDOM.nextBytes(raw);
		String challengeId = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
		Duration ttl = Duration.ofMinutes(sec.getOtpTtlMinutes());
		store.put(key(purpose, challengeId), crypto.hmacHex(challengeId + ":" + code) + "|" + subject, ttl);
		store.delete(attemptKey(purpose, challengeId));
		store.put(coolKey, "1", Duration.ofSeconds(sec.getOtpResendCooldownSeconds()));

		if (sec.isOtpDebugLog()) {
			log.warn("[개발용] {} 인증번호 {} -> {}", purpose.label, code, email);
		}
		mail.sendEmail(email, "[Ant Helper] " + purpose.label + " 인증번호",
			"인증번호: " + code + "\n\n" + sec.getOtpTtlMinutes() + "분 안에 입력해 주세요.\n"
				+ "본인이 요청하지 않았다면 이 메일을 무시하고, 비밀번호를 바꿔 주세요.");
		return new Issued(challengeId, (int)ttl.toSeconds(), sec.getOtpResendCooldownSeconds());
	}

	/** 번호를 검증하기 전에 이 인증번호가 누구를 위한 것인지 확인한다(없거나 만료되면 비어 있음). */
	public Optional<String> peekSubject(Purpose purpose, String challengeId) {
		if (challengeId == null || challengeId.isBlank()) {
			return Optional.empty();
		}
		return store.get(key(purpose, challengeId)).map(v -> v.substring(v.indexOf('|') + 1));
	}

	/** 맞으면 subject 를 돌려주고 번호를 폐기한다. 틀리거나 없으면 INVALID_OTP. */
	public String verify(Purpose purpose, String challengeId, String code) {
		if (challengeId == null || code == null || !code.matches("\\d{6}")) {
			throw ApiException.from(ErrorCode.INVALID_OTP);
		}
		String key = key(purpose, challengeId);
		String stored = store.get(key).orElseThrow(() -> ApiException.from(ErrorCode.INVALID_OTP));
		long attempts = store.increment(attemptKey(purpose, challengeId), Duration.ofMinutes(props.getSecurity().getOtpTtlMinutes()));
		if (attempts > props.getSecurity().getOtpMaxAttempts()) {
			store.delete(key);
			throw ApiException.of(ErrorCode.INVALID_OTP, "인증번호를 너무 많이 틀렸습니다. 인증번호를 다시 받아 주세요.");
		}
		int bar = stored.indexOf('|');
		String expected = stored.substring(0, bar);
		String actual = crypto.hmacHex(challengeId + ":" + code);
		if (!MessageDigest.isEqual(expected.getBytes(), actual.getBytes())) {
			throw ApiException.from(ErrorCode.INVALID_OTP);
		}
		store.delete(key);
		store.delete(attemptKey(purpose, challengeId));
		return stored.substring(bar + 1);
	}

	/** 인증에 성공했을 때: 메일 발송 횟수 카운터를 초기화한다. */
	public void clearSendCounters(String email, String ip) {
		guard.reset("otp-send:" + email);
		guard.reset("otp-send-ip:" + ip);
	}

	private static String key(Purpose purpose, String challengeId) {
		return "otp:" + purpose + ":" + challengeId;
	}

	private static String attemptKey(Purpose purpose, String challengeId) {
		return "otp:att:" + purpose + ":" + challengeId;
	}
}
