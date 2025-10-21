package com.backend.common.util.webClient;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;

import io.netty.channel.ChannelOption;
import com.backend.common.properties.AiServerProperties;
import com.backend.common.properties.KisProperties;
import com.backend.common.properties.PublicDataPortalProperties;
import com.backend.common.properties.RecaptchaProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.netty.http.client.HttpClient;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class WebClientConfig {

	private final KisProperties kisProperties;
	private final AiServerProperties aiServerProperties;
	private final PublicDataPortalProperties publicDataPortalProperties;
	private final RecaptchaProperties recaptchaProperties;

	private final HttpClient httpClient = HttpClient.create()
		.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000); // 5초

	@Bean
	public WebClient kisWebClient() {
		return WebClient.builder()
			.baseUrl(kisProperties.getUrl())
			.defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_UTF8_VALUE)
			.codecs(configurer -> configurer.defaultCodecs()
				.maxInMemorySize(2 * 1024 * 1024)) // 응답 payload가 클 경우 나는 에러 방지, 최대 2MB
			.clientConnector(new ReactorClientHttpConnector(httpClient))
			.build();
	}

	@Bean
	public WebClient aiServerWebClient() {
		return WebClient.builder()
			.baseUrl(aiServerProperties.getUrl())
			.defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_UTF8_VALUE)
			.codecs(configurer -> configurer.defaultCodecs()
				.maxInMemorySize(2 * 1024 * 1024)) // 응답 payload가 클 경우 나는 에러 방지, 최대 2MB
			.clientConnector(new ReactorClientHttpConnector(httpClient))
			.build();
	}

	@Bean
	public WebClient publicDataPortalWebClient() {
		return WebClient.builder()
			.baseUrl(publicDataPortalProperties.getUrl())
			.defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_UTF8_VALUE)
			.codecs(configurer -> configurer.defaultCodecs()
				.maxInMemorySize(2 * 1024 * 1024)) // 응답 payload가 클 경우 나는 에러 방지, 최대 2MB
			.clientConnector(new ReactorClientHttpConnector(httpClient))
			.build();
	}

	@Bean
	public WebClient recaptchaWebClient() {
		return WebClient.builder()
			.baseUrl(recaptchaProperties.getUrl())
			.defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_UTF8_VALUE)
			.codecs(configurer -> configurer.defaultCodecs()
				.maxInMemorySize(2 * 1024 * 1024)) // 응답 payload가 클 경우 나는 에러 방지, 최대 2MB
			.clientConnector(new ReactorClientHttpConnector(httpClient))
			.build();
	}
}