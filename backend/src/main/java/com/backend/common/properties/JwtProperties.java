package com.backend.common.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {

	private String issuer;
	private String secret;
	private Access access;
	private Refresh refresh;

	private String ACCESS_TOKEN_SUBJECT = "AccessToken";
	private String REFRESH_TOKEN_SUBJECT = "RefreshToken";
	private String MEMBER_ID_CLAIM = "member_id";
	private String BEARER = "Bearer ";

	/** 리프레시 토큰이 아무리 길게 설정돼 있어도 이 값(7일)을 넘기지 않는다. 쓸 때마다 새로 발급되므로 사실상 '7일 무활동 시 만료'다. */
	public static final int MAX_REFRESH_SECONDS = 7 * 24 * 3600;

	public int refreshSeconds() {
		int configured = refresh == null ? MAX_REFRESH_SECONDS : refresh.getExpiration();
		return configured <= 0 ? MAX_REFRESH_SECONDS : Math.min(configured, MAX_REFRESH_SECONDS);
	}

	@Getter
	@Setter
	public static class Access {
		private int expiration;
		private String header;
	}

	@Getter
	@Setter
	public static class Refresh {
		private int expiration;
		private String header;
	}
}
