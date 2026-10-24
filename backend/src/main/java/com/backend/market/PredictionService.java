package com.backend.market;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.backend.common.exception.ApiException;
import com.backend.common.exception.ErrorCode;
import com.backend.common.properties.AppProperties;
import com.backend.domains.member.domain.MemberAccount;
import com.backend.infra.prediction.PredictionClient;
import com.backend.infra.prediction.PredictionClient.CandleIn;
import com.backend.infra.prediction.PredictionClient.PredictRequest;
import com.backend.infra.prediction.PredictionClient.PredictResult;
import com.backend.infra.prediction.PredictionClient.ScreenItemIn;
import com.backend.infra.prediction.PredictionClient.ScreenRequest;
import com.backend.market.MarketDataService.Period;
import com.backend.market.dto.MarketDtos.Candle;
import com.backend.market.dto.MarketDtos.Prediction;
import com.backend.market.dto.MarketDtos.Recommendation;
import com.backend.market.dto.MarketDtos.Signal;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class PredictionService {

	private static final int CANDLE_COUNT = 120;

	private final MarketDataService marketData;
	private final PredictionClient client;
	private final AppProperties props;

	public Prediction predict(MemberAccount account, Market market, String symbol) {
		List<Candle> candles = marketData.candles(account, market, symbol, Period.D, CANDLE_COUNT);
		String name = marketData.nameOf(market, symbol);
		PredictResult r = client.predict(new PredictRequest(symbol, name, market.currency().name(), toIn(candles)));
		return new Prediction(
			symbol, name, market, market.currency(),
			money(market, r.currentPrice()), money(market, r.predictedPrice()), MarketDataService.round2(r.predictedChangeRate()),
			r.direction(), r.confidence(), r.summary(),
			r.signals() == null ? List.of()
				: r.signals().stream().map(s -> new Signal(s.name(), s.value(), s.tone())).toList(),
			"NEXT_DAY", OffsetDateTime.now(ZoneId.of("Asia/Seoul")));
	}

	/**
	 * 후보군 시세를 받아 Python 으로 스크리닝한다. group 은 "KRX" 또는 "US".
	 */
	public List<Recommendation> recommend(MemberAccount account, String group, int size) {
		boolean us = "US".equalsIgnoreCase(group);
		List<String> universe = us ? props.getRecommendation().getUsUniverse() : props.getRecommendation().getKrxUniverse();
		int max = account.isPaper() ? props.getRecommendation().getMaxCandidatesPaper()
			: props.getRecommendation().getMaxCandidatesReal();
		List<String> picked = universe.stream().limit(max).toList();

		Semaphore gate = new Semaphore(account.isPaper() ? 2 : 5);
		List<ScreenItemIn> items = new ArrayList<>();
		List<Market> marketOf = new ArrayList<>();
		try (var exec = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
			List<java.util.concurrent.Future<Object[]>> futures = new ArrayList<>();
			for (String sym : picked) {
				futures.add(exec.submit(() -> {
					gate.acquire();
					try {
						var stock = marketData.resolve(sym, us ? Market.NASDAQ : Market.KRX);
						List<Candle> c = marketData.candles(account, stock.market(), sym, Period.D, CANDLE_COUNT);
						return new Object[] {stock, c};
					} catch (Exception e) {
						log.info("추천 후보 {} 시세 조회 실패: {}", sym, e.getMessage());
						return null;
					} finally {
						gate.release();
					}
				}));
			}
			for (var f : futures) {
				Object[] row = f.get();
				if (row == null) {
					continue;
				}
				var stock = (com.backend.market.dto.MarketDtos.StockSummary)row[0];
				@SuppressWarnings("unchecked")
				List<Candle> c = (List<Candle>)row[1];
				items.add(new ScreenItemIn(stock.productNumber(), stock.name(), stock.currency().name(), toIn(c)));
				marketOf.add(stock.market());
			}
		} catch (InterruptedException | java.util.concurrent.ExecutionException e) {
			Thread.currentThread().interrupt();
			throw ApiException.from(ErrorCode.INTERNAL_SERVER_ERROR);
		}
		if (items.isEmpty()) {
			throw ApiException.of(ErrorCode.NOT_ENOUGH_CANDLES, "추천 후보 종목의 시세를 가져오지 못했습니다.");
		}

		var result = client.screen(new ScreenRequest(items, Math.max(1, Math.min(size, 20)), 0));
		List<Recommendation> out = new ArrayList<>();
		if (result.ranked() != null) {
			for (var s : result.ranked()) {
				int idx = indexOf(items, s.symbol());
				Market m = idx >= 0 ? marketOf.get(idx) : (us ? Market.NASDAQ : Market.KRX);
				out.add(new Recommendation(s.symbol(), s.name(), m, m.currency(), money(m, s.currentPrice()),
					s.score(), MarketDataService.round2(s.predictedChangeRate()), s.direction(), s.confidence(),
					s.reasons() == null ? List.of() : s.reasons()));
			}
		}
		return out;
	}

	private int indexOf(List<ScreenItemIn> items, String symbol) {
		for (int i = 0; i < items.size(); i++) {
			if (items.get(i).symbol().equalsIgnoreCase(symbol)) {
				return i;
			}
		}
		return -1;
	}

	private List<CandleIn> toIn(List<Candle> candles) {
		return candles.stream()
			.map(c -> new CandleIn(c.date(), c.open().doubleValue(), c.high().doubleValue(), c.low().doubleValue(),
				c.close().doubleValue(), c.volume()))
			.collect(Collectors.toList());
	}

	private BigDecimal money(Market market, double value) {
		return market.currency().round(BigDecimal.valueOf(value));
	}

	public static String directionLabel(String direction) {
		if (direction == null) {
			return "";
		}
		return switch (direction.toUpperCase(Locale.ROOT)) {
			case "UP" -> "상승";
			case "DOWN" -> "하락";
			default -> "보합";
		};
	}
}
