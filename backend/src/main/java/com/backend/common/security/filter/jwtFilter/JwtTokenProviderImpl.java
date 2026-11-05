package com.backend.common.security.filter.jwtFilter;

import static com.backend.common.exception.ErrorCode.*;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Service;

import com.backend.common.crypto.CryptoService;
import com.backend.common.exception.ApiException;
import com.backend.common.properties.JwtProperties;
import com.backend.domains.member.domain.LogoutToken;
import com.backend.domains.member.domain.Member;
import com.backend.domains.member.repository.LogoutRepository;
import com.backend.domains.member.repository.MemberRepository;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class JwtTokenProviderImpl implements JwtTokenProvider {

	private final JwtProperties jwtProperties;
	private final MemberRepository memberRepository;
	private final SecretKey key;
	private final LogoutRepository logoutRepository;

	public JwtTokenProviderImpl(JwtProperties jwtProperties, MemberRepository memberRepository,
		LogoutRepository logoutRepository) {
		this.jwtProperties = jwtProperties;
		this.memberRepository = memberRepository;
		this.key = Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));
		this.logoutRepository = logoutRepository;
	}

	@Override
	public String createAccessToken(Long memberId) {
		return Jwts.builder()
			.subject(jwtProperties.getACCESS_TOKEN_SUBJECT())
			.claim(jwtProperties.getMEMBER_ID_CLAIM(), memberId)
			.expiration(new Date(System.currentTimeMillis() + jwtProperties.getAccess().getExpiration() * 1000L))
			.signWith(key, Jwts.SIG.HS256)
			.compact();
	}

	@Override
	public String createRefreshToken() {
		return Jwts.builder()
			.subject(jwtProperties.getREFRESH_TOKEN_SUBJECT())
			.id(UUID.randomUUID().toString())
			.expiration(new Date(System.currentTimeMillis() + jwtProperties.refreshSeconds() * 1000L))
			.signWith(key, Jwts.SIG.HS256)
			.compact();
	}

	@Override
	public Optional<String> extractAccessToken(HttpServletRequest request) {
		String header = request.getHeader(jwtProperties.getAccess().getHeader());
		if (header == null || !header.startsWith(jwtProperties.getBEARER())) {
			return Optional.empty();
		}
		return Optional.of(header.substring(jwtProperties.getBEARER().length()).trim());
	}

	@Override
	public Optional<Long> validAccessMemberId(String accessToken) {
		Claims claims = parse(accessToken);
		if (claims == null || !jwtProperties.getACCESS_TOKEN_SUBJECT().equals(claims.getSubject())) {
			return Optional.empty();
		}
		Number id = claims.get(jwtProperties.getMEMBER_ID_CLAIM(), Number.class);
		return id == null ? Optional.empty() : Optional.of(id.longValue());
	}

	@Override
	public boolean isValidRefreshToken(String refreshToken) {
		Claims claims = parse(refreshToken);
		return claims != null && jwtProperties.getREFRESH_TOKEN_SUBJECT().equals(claims.getSubject());
	}

	@Override
	public Authentication getAuthentication(Long memberId) {
		Member member = memberRepository.findById(memberId).orElseThrow(() -> ApiException.from(MEMBER_NOT_FOUND));
		List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority(member.getMemberRole().getValue()));
		return UsernamePasswordAuthenticationToken.authenticated(memberId, "", authorities);
	}

	@Override
	public boolean isLogout(String accessToken) {
		return logoutRepository.existsByTokenHash(CryptoService.sha256Hex(accessToken));
	}

	@Override
	public void revoke(String accessToken) {
		if (accessToken != null && !accessToken.isBlank()) {
			logoutRepository.save(new LogoutToken(UUID.randomUUID().toString(), CryptoService.sha256Hex(accessToken),
				jwtProperties.getAccess().getExpiration() + 60L));
		}
	}

	/** 서명이 맞고 만료되지 않았으면 claims, 아니면 null. */
	private Claims parse(String token) {
		if (token == null || token.isBlank()) {
			return null;
		}
		try {
			return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
		} catch (JwtException | IllegalArgumentException e) {
			log.debug("JWT 검증 실패: {}", e.getClass().getSimpleName());
			return null;
		}
	}
}
