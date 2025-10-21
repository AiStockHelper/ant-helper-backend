package com.backend.common.util.encoder;

import static com.backend.common.exception.ErrorCode.*;

import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.backend.common.exception.ApiException;

public class EncryptUtil {

	private static final String ALGORITHM = "AES";
	private static final int KEY_SIZE = 128;

	// 키 생성
	public static SecretKey generateKey() {
		try {
			KeyGenerator keyGenerator = KeyGenerator.getInstance(ALGORITHM);
			keyGenerator.init(KEY_SIZE); // 키 크기 (128, 192, 256)
			return keyGenerator.generateKey();
		} catch (Exception e) {
			throw ApiException.from(INTERNAL_SERVER_ERROR);
		}
	}

	// 암호화
	public static String encrypt(String data, SecretKey key) {
		try {
			Cipher cipher = Cipher.getInstance(ALGORITHM);
			cipher.init(Cipher.ENCRYPT_MODE, key);
			byte[] encryptedData = cipher.doFinal(data.getBytes());
			return Base64.getEncoder().encodeToString(encryptedData);
		} catch (Exception e) {
			throw ApiException.from(INTERNAL_SERVER_ERROR);
		}
	}

	// 복호화
	public static String decrypt(String encryptedData, SecretKey key) {
		try {
			Cipher cipher = Cipher.getInstance(ALGORITHM);
			cipher.init(Cipher.DECRYPT_MODE, key);
			byte[] decodedData = Base64.getDecoder().decode(encryptedData);
			byte[] decryptedData = cipher.doFinal(decodedData);
			return new String(decryptedData);
		} catch (Exception e) {
			throw ApiException.from(INTERNAL_SERVER_ERROR);
		}
	}

	// 키를 문자열로 변환
	public static String keyToString(SecretKey key) {
		return Base64.getEncoder().encodeToString(key.getEncoded());
	}

	// 문자열을 키로 변환
	public static SecretKey stringToKey(String keyStr) {
		byte[] decodedKey = Base64.getDecoder().decode(keyStr);
		return new SecretKeySpec(decodedKey, 0, decodedKey.length, ALGORITHM);
	}
}