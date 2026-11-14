package com.backend.common.security.cors;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.backend.common.properties.CorsProperties;
import com.backend.common.security.SecurityPolicy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 개발 모드: 설정 파일의 허용 목록을 그대로 쓴다(localhost 포함).
 * 운영(강화) 모드: 설정에 무엇이 적혀 있든 https 주소만 허용하고 localhost·사설 주소·와일드카드는 걸러낸다.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class CorsConfig {

	private static final List<String> STRICT_HEADERS = List.of("Authorization", "Content-Type", "Accept", "X-Request-Id");

	private final CorsProperties corsProperties;
	private final SecurityPolicy policy;

	@Bean
	public UrlBasedCorsConfigurationSource corsConfigurationSources() {
		CorsConfiguration config = new CorsConfiguration();
		List<String> origins = corsProperties.getAllowedOrigins() == null ? List.of() : corsProperties.getAllowedOrigins();
		if (policy.hardened()) {
			origins = origins.stream().map(String::trim).filter(CorsConfig::isSafeProductionOrigin).toList();
			config.setAllowedHeaders(STRICT_HEADERS);
			log.info("CORS(운영) 허용 출처: {}", origins);
		} else {
			config.setAllowedHeaders(corsProperties.getAllowedHeaders());
		}
		config.setAllowedOrigins(origins);
		config.setAllowedMethods(corsProperties.getAllowedMethods());
		config.setAllowCredentials(corsProperties.getRequireCredential());
		config.setExposedHeaders(List.of("X-Request-Id"));
		config.setMaxAge(3600L);

		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		corsProperties.getAllowedUrls().forEach(url -> source.registerCorsConfiguration(url, config));
		return source;
	}

	public static boolean isSafeProductionOrigin(String origin) {
		if (origin == null || !origin.startsWith("https://") || origin.contains("*")) {
			return false;
		}
		String host = origin.substring("https://".length()).split("[:/]")[0].toLowerCase();
		return !(host.equals("localhost") || host.startsWith("127.") || host.startsWith("10.") || host.startsWith("192.168.")
			|| host.endsWith(".local"));
	}
}
