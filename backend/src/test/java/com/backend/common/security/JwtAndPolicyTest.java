package com.backend.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.Date;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;

import com.backend.common.properties.JwtProperties;
import com.backend.common.properties.SecurityProperties;
import com.backend.common.security.cors.CorsConfig;
import com.backend.common.security.filter.jwtFilter.JwtTokenProviderImpl;
import com.backend.domains.member.repository.LogoutRepository;
import com.backend.domains.member.repository.MemberRepository;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

class JwtAndPolicyTest {

	private static final String SECRET = "0123456789abcdef0123456789abcdef0123456789abcdef";

	private JwtProperties jwt;
	private JwtTokenProviderImpl provider;

	@BeforeEach
	void setUp() {
		jwt = new JwtProperties();
		jwt.setSecret(SECRET);
		JwtProperties.Access access = new JwtProperties.Access();
		access.setExpiration(3600);
		access.setHeader("Authorization");
		jwt.setAccess(access);
		JwtProperties.Refresh refresh = new JwtProperties.Refresh();
		refresh.setExpiration(1_209_600);
		refresh.setHeader("Authorization-refresh");
		jwt.setRefresh(refresh);
		provider = new JwtTokenProviderImpl(jwt, mock(MemberRepository.class), mock(LogoutRepository.class));
	}

	@Test
	void accessTokenRoundTripsAndRefreshTokenIsNotAnAccessToken() {
		String access = provider.createAccessToken(7L);
		assertThat(provider.validAccessMemberId(access)).contains(7L);
		assertThat(provider.validAccessMemberId(provider.createRefreshToken())).isEmpty();
		assertThat(provider.isValidRefreshToken(access)).isFalse();
		assertThat(provider.isValidRefreshToken(provider.createRefreshToken())).isTrue();
	}

	@Test
	void expiredAndTamperedTokensAreRejected() {
		String expired = Jwts.builder().subject(jwt.getACCESS_TOKEN_SUBJECT()).claim(jwt.getMEMBER_ID_CLAIM(), 7L)
			.expiration(new Date(System.currentTimeMillis() - 1000))
			.signWith(Keys.hmacShaKeyFor(SECRET.getBytes()), Jwts.SIG.HS256).compact();
		assertThat(provider.validAccessMemberId(expired)).isEmpty();

		String ok = provider.createAccessToken(7L);
		assertThat(provider.validAccessMemberId(ok.substring(0, ok.length() - 2) + "xx")).isEmpty();
		assertThat(provider.validAccessMemberId("not-a-jwt")).isEmpty();
		assertThat(provider.validAccessMemberId(null)).isEmpty();
	}

	@Test
	void tokenSignedWithAnotherKeyIsRejected() {
		String forged = Jwts.builder().subject(jwt.getACCESS_TOKEN_SUBJECT()).claim(jwt.getMEMBER_ID_CLAIM(), 1L)
			.expiration(new Date(System.currentTimeMillis() + 60_000))
			.signWith(Keys.hmacShaKeyFor("another-secret-another-secret-another-secret".getBytes()), Jwts.SIG.HS256).compact();
		assertThat(provider.validAccessMemberId(forged)).isEmpty();
	}

	@Test
	void unsignedTokenIsRejected() {
		String none = Jwts.builder().subject(jwt.getACCESS_TOKEN_SUBJECT()).claim(jwt.getMEMBER_ID_CLAIM(), 1L).compact();
		assertThat(provider.validAccessMemberId(none)).isEmpty();
	}

	@Test
	void refreshLifetimeIsCappedAtSevenDays() {
		assertThat(jwt.refreshSeconds()).isEqualTo(7 * 24 * 3600);
		jwt.getRefresh().setExpiration(3600);
		assertThat(jwt.refreshSeconds()).isEqualTo(3600);
	}

	@Test
	void bearerHeaderIsParsed() {
		MockHttpServletRequest req = new MockHttpServletRequest();
		req.addHeader("Authorization", "Bearer abc.def");
		assertThat(provider.extractAccessToken(req)).contains("abc.def");
		assertThat(provider.extractAccessToken(new MockHttpServletRequest())).isEmpty();
	}

	// ─────────────────────────── SecurityPolicy / CORS ───────────────────────────

	private static SecurityPolicy policy(String... profiles) {
		MockEnvironment env = new MockEnvironment();
		env.setActiveProfiles(profiles);
		SecurityProperties props = new SecurityProperties();
		props.setPermitUrls(new String[] {"/api/auth/login", " /api-docs ", "/swagger-ui/**", "/api/email/verification"});
		return new SecurityPolicy(env, props);
	}

	@Test
	void productionHidesSwaggerAndAlwaysOpensHealth() {
		SecurityPolicy prod = policy("prod-env", "common", "prod");
		assertThat(prod.hardened()).isTrue();
		assertThat(prod.permitUrls()).contains("/api/health", "/api/auth/login/verify", "/api/email/verification")
			.doesNotContain("/api-docs", "/swagger-ui/**");

		SecurityPolicy noProfile = policy();
		assertThat(noProfile.hardened()).isTrue();
	}

	@Test
	void hardenedFlagOverridesProfiles() {
		MockEnvironment local = new MockEnvironment();
		local.setActiveProfiles("local-env");
		local.setProperty("ANT_HARDENED", "true");
		assertThat(SecurityPolicy.isHardened(local)).isTrue();

		MockEnvironment prod = new MockEnvironment();
		prod.setActiveProfiles("prod-env");
		prod.setProperty("ANT_HARDENED", "false");
		assertThat(SecurityPolicy.isHardened(prod)).isFalse();
	}

	@Test
	void localKeepsSwagger() {
		SecurityPolicy local = policy("local-env", "common", "local");
		assertThat(local.hardened()).isFalse();
		assertThat(local.permitUrls()).contains("/api-docs", "/swagger-ui/**");
	}

	@Test
	void productionCorsAllowsOnlyPublicHttpsOrigins() {
		assertThat(CorsConfig.isSafeProductionOrigin("https://app.example.com")).isTrue();
		assertThat(CorsConfig.isSafeProductionOrigin("http://app.example.com")).isFalse();
		assertThat(CorsConfig.isSafeProductionOrigin("https://localhost:3000")).isFalse();
		assertThat(CorsConfig.isSafeProductionOrigin("https://127.0.0.1")).isFalse();
		assertThat(CorsConfig.isSafeProductionOrigin("https://192.168.0.5")).isFalse();
		assertThat(CorsConfig.isSafeProductionOrigin("https://*.example.com")).isFalse();
		assertThat(CorsConfig.isSafeProductionOrigin("*")).isFalse();
	}
}
