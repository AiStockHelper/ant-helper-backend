package com.backend.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import com.backend.common.crypto.CryptoService;
import com.backend.common.properties.AppProperties;
import com.backend.common.properties.JwtProperties;
import com.backend.common.util.encoder.EncryptUtil;

class CryptoServiceTest {

	private static CryptoService service(byte fill) {
		AppProperties props = new AppProperties();
		byte[] key = new byte[32];
		java.util.Arrays.fill(key, fill);
		props.getSecurity().setCryptoKey(Base64.getEncoder().encodeToString(key));
		return new CryptoService(props, new JwtProperties(), new MockEnvironment());
	}

	@Test
	void roundTripsAndUsesRandomIv() {
		CryptoService c = service((byte)1);
		String a = c.encrypt("my-app-key");
		String b = c.encrypt("my-app-key");
		assertThat(a).startsWith("v2:").isNotEqualTo(b).doesNotContain("my-app-key");
		assertThat(c.decrypt(a)).isEqualTo("my-app-key");
		assertThat(c.decrypt(b)).isEqualTo("my-app-key");
	}

	@Test
	void anotherMasterKeyCannotDecrypt() {
		String enc = service((byte)1).encrypt("secret");
		assertThatThrownBy(() -> service((byte)2).decrypt(enc)).isInstanceOf(RuntimeException.class);
	}

	@Test
	void tamperedCiphertextIsRejected() {
		CryptoService c = service((byte)1);
		byte[] raw = Base64.getDecoder().decode(c.encrypt("secret").substring(3));
		raw[raw.length - 1] ^= 1;
		String tampered = "v2:" + Base64.getEncoder().encodeToString(raw);
		assertThatThrownBy(() -> c.decrypt(tampered)).isInstanceOf(RuntimeException.class);
	}

	@Test
	void wrongSizedKeyIsRefused() {
		AppProperties props = new AppProperties();
		props.getSecurity().setCryptoKey(Base64.getEncoder().encodeToString(new byte[16]));
		assertThatThrownBy(() -> new CryptoService(props, new JwtProperties(), new MockEnvironment()))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void productionRefusesToStartWithoutAKey() {
		MockEnvironment prod = new MockEnvironment();
		prod.setActiveProfiles("prod-env");
		// ANT_CRYPTO_KEY 환경변수가 개발 PC 에 설정돼 있으면 이 검사는 의미가 없으므로 건너뛴다.
		org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv("ANT_CRYPTO_KEY") == null);
		assertThatThrownBy(() -> new CryptoService(new AppProperties(), new JwtProperties(), prod))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void legacyEcbValuesAreStillReadable() {
		var legacyKey = EncryptUtil.generateKey();
		String legacy = EncryptUtil.encrypt("old-key", legacyKey);
		assertThat(CryptoService.isV2(legacy)).isFalse();
		assertThat(EncryptUtil.decrypt(legacy, EncryptUtil.stringToKey(EncryptUtil.keyToString(legacyKey)))).isEqualTo("old-key");
	}

	@Test
	void hmacIsDeterministicPerKey() {
		assertThat(service((byte)1).hmacHex("x")).isEqualTo(service((byte)1).hmacHex("x"))
			.isNotEqualTo(service((byte)2).hmacHex("x"));
	}
}
