package com.backend.common.crypto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import com.backend.common.exception.ApiException;
import com.backend.common.exception.ErrorCode;
import com.backend.common.properties.AppProperties;
import com.backend.common.properties.JwtProperties;

import lombok.extern.slf4j.Slf4j;

/**
 * 증권사 앱키·시크릿 같은 비밀 값을 AES-256-GCM 으로 암호화한다.
 * 마스터 키는 DB 밖(환경변수/설정)에만 있고, 저장 형식은 "v2:" + Base64(IV 12바이트 + 암호문 + 인증 태그)이다.
 * 이전 방식(AES-ECB, 키가 같은 행에 저장)으로 저장된 값은 마이그레이션이 끝날 때까지 읽기만 지원한다.
 */
@Slf4j
@Component
public class CryptoService {

	public static final String PREFIX = "v2:";
	private static final int IV_BYTES = 12;
	private static final int TAG_BITS = 128;

	private static volatile CryptoService instance;

	private final SecretKey masterKey;
	private final SecureRandom random = new SecureRandom();

	public CryptoService(AppProperties props, JwtProperties jwt, Environment env) {
		String configured = props.getSecurity().getCryptoKey();
		if (configured == null || configured.isBlank()) {
			configured = System.getenv("ANT_CRYPTO_KEY");
		}
		if (configured != null && !configured.isBlank()) {
			byte[] raw = Base64.getDecoder().decode(configured.trim());
			if (raw.length != 32) {
				throw new IllegalStateException("app.security.crypto-key 는 Base64 로 인코딩한 32바이트 값이어야 합니다.");
			}
			this.masterKey = new SecretKeySpec(raw, "AES");
		} else if (com.backend.common.security.SecurityPolicy.isHardened(env)) {
			throw new IllegalStateException("운영 환경에는 환경변수 ANT_CRYPTO_KEY(또는 app.security.crypto-key)에 Base64 32바이트 키가 반드시 필요합니다.");
		} else {
			// 개발 환경 편의: JWT 시크릿에서 파생한 키를 쓴다. 운영 데이터에는 절대 쓰지 말 것.
			log.warn("app.security.crypto-key 가 없어 개발용 임시 키를 사용합니다(운영 금지).");
			this.masterKey = new SecretKeySpec(sha256(("dev-crypto:" + jwt.getSecret()).getBytes(StandardCharsets.UTF_8)), "AES");
		}
		instance = this;
	}

	/** 엔티티처럼 스프링 빈을 주입받을 수 없는 곳에서 쓰는 접근자 */
	public static CryptoService get() {
		CryptoService s = instance;
		if (s == null) {
			throw new IllegalStateException("CryptoService 가 아직 초기화되지 않았습니다.");
		}
		return s;
	}

	public static boolean isV2(String stored) {
		return stored != null && stored.startsWith(PREFIX);
	}

	public String encrypt(String plain) {
		try {
			byte[] iv = new byte[IV_BYTES];
			random.nextBytes(iv);
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, masterKey, new GCMParameterSpec(TAG_BITS, iv));
			byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
			byte[] out = new byte[iv.length + ct.length];
			System.arraycopy(iv, 0, out, 0, iv.length);
			System.arraycopy(ct, 0, out, iv.length, ct.length);
			return PREFIX + Base64.getEncoder().encodeToString(out);
		} catch (Exception e) {
			throw ApiException.from(ErrorCode.INTERNAL_SERVER_ERROR);
		}
	}

	public String decrypt(String stored) {
		if (!isV2(stored)) {
			throw new IllegalArgumentException("지원하지 않는 암호문 형식입니다.");
		}
		try {
			byte[] in = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE, masterKey, new GCMParameterSpec(TAG_BITS, in, 0, IV_BYTES));
			return new String(cipher.doFinal(in, IV_BYTES, in.length - IV_BYTES), StandardCharsets.UTF_8);
		} catch (Exception e) {
			throw ApiException.of(ErrorCode.INTERNAL_SERVER_ERROR, "저장된 비밀 값을 복호화하지 못했습니다. 암호화 키 설정을 확인하세요.");
		}
	}

	/** 인증번호처럼 짧은 값을 저장할 때 쓰는 키 기반 해시. 마스터 키 없이는 되돌리거나 대조할 수 없다. */
	public String hmacHex(String value) {
		try {
			javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(masterKey.getEncoded(), "HmacSHA256"));
			byte[] h = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder(h.length * 2);
			for (byte b : h) {
				sb.append(String.format("%02x", b));
			}
			return sb.toString();
		} catch (Exception e) {
			throw ApiException.from(ErrorCode.INTERNAL_SERVER_ERROR);
		}
	}

	public static byte[] sha256(byte[] data) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(data);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	/** 토큰·인증번호처럼 원문을 저장하면 안 되는 값을 16진수 해시로 바꾼다. */
	public static String sha256Hex(String value) {
		byte[] h = sha256(value.getBytes(StandardCharsets.UTF_8));
		StringBuilder sb = new StringBuilder(h.length * 2);
		for (byte b : h) {
			sb.append(String.format("%02x", b));
		}
		return sb.toString();
	}
}
