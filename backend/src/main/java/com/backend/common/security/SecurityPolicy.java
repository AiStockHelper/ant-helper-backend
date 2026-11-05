package com.backend.common.security;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import com.backend.common.properties.SecurityProperties;

/**
 * 운영(강화) 모드와 개발 모드를 가르는 단일 기준.
 * local / dev / test 프로필이 하나도 없으면 운영으로 보고 보수적으로 동작한다
 * (Swagger 비활성, CORS 는 https 도메인만, 보안 헤더 전부 적용).
 * 설정 파일은 서버에만 있으므로, 안전한 동작은 설정이 아니라 이 코드에서 기본값으로 보장한다.
 */
@Component
public class SecurityPolicy {

	/** 설정에 적혀 있지 않아도 항상 열려 있어야 하는 경로 */
	private static final List<String> ALWAYS_PUBLIC = List.of(
		"/api/health", "/api/auth/login", "/api/auth/login/verify", "/api/auth/signup", "/api/auth/refresh", "/error");

	private final boolean hardened;
	private final List<String> permitUrls;

	public SecurityPolicy(Environment env, SecurityProperties props) {
		this.hardened = isHardened(env);
		Set<String> urls = new LinkedHashSet<>(ALWAYS_PUBLIC);
		if (props.getPermitUrls() != null) {
			Arrays.stream(props.getPermitUrls()).map(String::trim).filter(s -> !s.isEmpty()).forEach(urls::add);
		}
		if (hardened) {
			urls.removeIf(u -> u.startsWith("/api-docs") || u.startsWith("/swagger-ui"));
		}
		this.permitUrls = new ArrayList<>(urls);
	}

	public static boolean isHardened(Environment env) {
		return Arrays.stream(env.getActiveProfiles()).noneMatch(p -> {
			String s = p.toLowerCase();
			return s.contains("local") || s.contains("dev") || s.contains("test");
		});
	}

	public boolean hardened() {
		return hardened;
	}

	public List<String> permitUrls() {
		return permitUrls;
	}

	public String[] permitUrlArray() {
		return permitUrls.toArray(new String[0]);
	}
}
