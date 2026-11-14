package com.backend.common.security;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * 운영(강화) 모드에서 서버 설정 파일이 무엇이라고 적혀 있든 꼭 지켜야 하는 값을 덮어쓴다.
 * 설정 파일은 저장소에 없고 서버에만 있으므로 코드로 보장해야 한다.
 */
public class HardeningEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
		if (!SecurityPolicy.isHardened(env)) {
			return;
		}
		Map<String, Object> forced = new LinkedHashMap<>();
		forced.put("springdoc.api-docs.enabled", "false");
		forced.put("springdoc.swagger-ui.enabled", "false");
		// nginx/ALB 같은 신뢰하는 프록시(사설 대역·루프백)가 보낸 X-Forwarded-* 만 톰캣이 반영한다.
		forced.put("server.forward-headers-strategy", "native");
		forced.put("server.error.include-message", "never");
		forced.put("server.error.include-stacktrace", "never");
		forced.put("server.tomcat.max-http-form-post-size", "64KB");
		env.getPropertySources().addFirst(new MapPropertySource("hardening", forced));
	}

	@Override
	public int getOrder() {
		return Ordered.LOWEST_PRECEDENCE;
	}
}
