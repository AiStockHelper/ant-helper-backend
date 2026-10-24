package com.backend.infra.prediction;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.backend.common.exception.ApiException;
import com.backend.common.exception.ErrorCode;
import com.backend.common.properties.AppProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.extern.slf4j.Slf4j;

/**
 * 계산 전용 Python 예측 서버 호출. KIS 데이터는 Spring 이 가져와서 캔들로 넘겨준다.
 */
@Slf4j
@Component
public class PredictionClient {

	private final RestClient rest;

	public PredictionClient(AppProperties props) {
		// uvicorn(h11)은 HTTP/2 업그레이드(h2c)를 지원하지 않으므로 HTTP/1.1 로 고정한다.
		HttpClient http = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(Duration.ofSeconds(3))
			.build();
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
		factory.setReadTimeout(Duration.ofSeconds(props.getPrediction().getTimeoutSeconds()));
		this.rest = RestClient.builder()
			.baseUrl(props.getPrediction().getBaseUrl())
			.requestFactory(factory)
			.build();
	}

	public record CandleIn(String date, double open, double high, double low, double close, long volume) {
	}

	public record PredictRequest(String symbol, String name, String currency, List<CandleIn> candles) {
	}

	public record ScreenItemIn(String symbol, String name, String currency, List<CandleIn> candles) {
	}

	public record ScreenRequest(List<ScreenItemIn> items, int topN, double minScore) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record SignalOut(String name, String value, String tone) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record PredictResult(
		String symbol, String name, double currentPrice, double predictedPrice, double predictedChangeRate,
		String direction, double confidence, String summary, List<SignalOut> signals, Map<String, Double> indicators
	) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ScreenItemOut(
		String symbol, String name, double currentPrice, double score, double predictedChangeRate,
		String direction, double confidence, List<String> reasons
	) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ScreenResult(List<ScreenItemOut> ranked) {
	}

	public PredictResult predict(PredictRequest request) {
		return call(() -> rest.post().uri("/v1/predict").contentType(MediaType.APPLICATION_JSON).body(request)
			.retrieve().body(PredictResult.class));
	}

	public ScreenResult screen(ScreenRequest request) {
		return call(() -> rest.post().uri("/v1/screen").contentType(MediaType.APPLICATION_JSON).body(request)
			.retrieve().body(ScreenResult.class));
	}

	public boolean isUp() {
		try {
			rest.get().uri("/health").retrieve().toBodilessEntity();
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	private <T> T call(java.util.function.Supplier<T> action) {
		try {
			return action.get();
		} catch (HttpClientErrorException.UnprocessableEntity e) {
			log.info("예측 서버 422: {}", e.getResponseBodyAsString());
			throw ApiException.of(ErrorCode.NOT_ENOUGH_CANDLES, "예측에 필요한 시세 데이터가 부족합니다.");
		} catch (ResourceAccessException e) {
			log.warn("예측 서버 연결 실패: {}", e.getMessage());
			throw ApiException.from(ErrorCode.PREDICTION_UNAVAILABLE);
		} catch (RuntimeException e) {
			log.warn("예측 서버 호출 실패: {}", e.toString());
			throw ApiException.from(ErrorCode.PREDICTION_UNAVAILABLE);
		}
	}
}
