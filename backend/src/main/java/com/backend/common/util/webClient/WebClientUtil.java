package com.backend.common.util.webClient;

import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import com.backend.common.exception.ApiException;
import com.backend.common.exception.ErrorCode;
import com.backend.domains.broker.dto.BrokerDTO.KisErrorResponse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebClientUtil {

	@Qualifier("aiServerWebClient")
	private final WebClient aiServerWebClient;

	public <T, V> T postFromAiServer(Map<String, String> headersMap, String url, V request, Class<T> responseType) {
		return aiServerWebClient
			.post()
			.uri(url)
			.headers(headers -> headersMap.forEach(headers::set))
			.bodyValue(request)
			.retrieve()
			.onStatus(
				HttpStatusCode::is4xxClientError,
				response -> response
					.bodyToMono(KisErrorResponse.class)
					.flatMap(errorBody -> {
						log.error("4xx Client Error: {}", errorBody);
						return Mono.error(ApiException.from(ErrorCode.BAD_REQUEST));
					})
			)
			.onStatus(
				HttpStatusCode::is5xxServerError,
				response -> response
					.bodyToMono(KisErrorResponse.class)
					.flatMap(errorBody -> {
						log.error("5xx Server Error: {}", errorBody);
						return Mono.error(ApiException.from(ErrorCode.INTERNAL_SERVER_ERROR));
					})
			)
			.bodyToMono(responseType)
			.block();
	}
}
