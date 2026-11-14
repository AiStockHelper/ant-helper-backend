package com.backend.market;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.backend.common.util.Json;
import com.backend.domains.member.domain.MemberAccount;
import com.fasterxml.jackson.core.type.TypeReference;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 달러-원 환율(1달러가 몇 원인지). 화면에서 달러 종목을 원화로 환산해 보여 줄 때 쓴다.
 * 증권사가 잔고 조회 때 알려 주는 환율을 우선 쓰고(들어올 때마다 기억), 없으면 무료 공개 환율 API 로 가져온다.
 * 10분 동안 캐시한다. 환산 금액은 참고용이며 실제 체결·정산 환율과 다를 수 있다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExchangeRateService {

	public record Rate(double usdKrw, String source, OffsetDateTime asOf) {
	}

	private static final Duration TTL = Duration.ofMinutes(10);
	private static final String PUBLIC_API = "https://open.er-api.com/v6/latest/USD";

	private final TradingService trading;
	private final Json json;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

	private volatile Rate cached;
	private volatile long cachedAtMillis;

	/** 잔고 조회 등에서 증권사가 알려 준 환율을 기억해 둔다. */
	public void remember(Double usdKrw, String source) {
		if (usdKrw != null && usdKrw > 100 && usdKrw < 10_000) {
			cached = new Rate(usdKrw, source, OffsetDateTime.now(ZoneId.of("Asia/Seoul")));
			cachedAtMillis = System.currentTimeMillis();
		}
	}

	public Optional<Rate> current(MemberAccount account) {
		Rate c = cached;
		if (c != null && System.currentTimeMillis() - cachedAtMillis < TTL.toMillis()) {
			return Optional.of(c);
		}
		if (account != null) {
			try {
				Double fx = trading.exchangeRate(account);
				remember(fx, "한국투자증권");
			} catch (RuntimeException e) {
				log.info("증권사 환율 조회 실패, 공개 환율로 대체합니다: {}", e.getMessage());
			}
		}
		if (cached != null && System.currentTimeMillis() - cachedAtMillis < TTL.toMillis()) {
			return Optional.of(cached);
		}
		try {
			HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(PUBLIC_API)).timeout(Duration.ofSeconds(5)).GET().build(),
				HttpResponse.BodyHandlers.ofString());
			if (res.statusCode() == 200) {
				var root = json.read(res.body(), new TypeReference<java.util.Map<String, Object>>() {}, java.util.Map.<String, Object>of());
				Object rates = root.get("rates");
				if (rates instanceof java.util.Map<?, ?> m && m.get("KRW") instanceof Number n) {
					remember(BigDecimal.valueOf(n.doubleValue()).setScale(2, java.math.RoundingMode.HALF_UP).doubleValue(), "공개 환율");
				}
			}
		} catch (Exception e) {
			log.info("공개 환율 조회 실패: {}", e.toString());
		}
		return Optional.ofNullable(cached);
	}
}
