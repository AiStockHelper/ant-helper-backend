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
	private final com.backend.domains.screening.ScreeningService screening;

	public Prediction predict(MemberAccount account, Market market, String symbol) {
		log.info("예측 요청 account={} market={} symbol={}", account.getId(), market, symbol);
		List<Candle> candles = withoutOpenSessionBar(marketData.candles(account, market, symbol, Period.D, CANDLE_COUNT), market);
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
		return recommend(account, group, size, null);
	}

	/**
	 * 상승이 예상되는 종목을 점수순으로.
	 * 야간 스크리닝 결과가 있으면 그것을(전 종목 대상, 즉시 응답) 쓰고, 없거나 낡았거나 후보를 직접 지정했으면
	 * 대표 후보군을 지금 실시간으로 분석한다.
	 */
	public List<Recommendation> recommend(MemberAccount account, String group, int size, List<String> universeOverride) {
		boolean custom = universeOverride != null && !universeOverride.isEmpty();
		if (!custom && screening.freshDate(com.backend.domains.screening.ScreeningService.normalizeGroup(group)).isPresent()) {
			return screening.risers(com.backend.domains.screening.ScreeningService.normalizeGroup(group), Math.max(1, Math.min(size, 50)));
		}
		return recommendLive(account, group, size, universeOverride).stream()
			.filter(r -> "UP".equalsIgnoreCase(r.direction()) && r.predictedChangeRate() > 0)
			.toList();
	}

	/** 방향과 무관하게 점수가 높은 순의 분석 풀(AI 의견의 참고 자료). */
	public List<Recommendation> analysisPool(MemberAccount account, String group, int size) {
		if (screening.freshDate(com.backend.domains.screening.ScreeningService.normalizeGroup(group)).isPresent()) {
			return screening.topByScore(com.backend.domains.screening.ScreeningService.normalizeGroup(group), Math.max(1, Math.min(size, 50)));
		}
		return recommendLive(account, group, size, null);
	}

	/** 화면에 보여줄 소수의 종목에 최신 현재가를 덮어쓴다(증권사 시세). 실패한 종목은 분석 기준 종가를 그대로 둔다. */
	public List<Recommendation> withLivePrices(MemberAccount account, List<Recommendation> recs) {
		if (recs.isEmpty()) {
			return recs;
		}
		try (var exec = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
			List<java.util.concurrent.Future<Recommendation>> futures = new ArrayList<>();
			for (Recommendation r : recs) {
				futures.add(exec.submit(() -> {
					try {
						BigDecimal live = marketData.quote(account, r.market(), r.productNumber()).price();
						return new Recommendation(r.productNumber(), r.name(), r.market(), r.currency(), live, r.score(),
							r.predictedChangeRate(), r.direction(), r.confidence(), r.reasons(), r.asOf());
					} catch (RuntimeException e) {
						log.info("현재가 갱신 실패 {}: {}", r.productNumber(), e.getMessage());
						return r;
					}
				}));
			}
			List<Recommendation> out = new ArrayList<>();
			for (var f : futures) {
				out.add(f.get());
			}
			return out;
		} catch (InterruptedException | java.util.concurrent.ExecutionException e) {
			Thread.currentThread().interrupt();
			return recs;
		}
	}

	/** 장이 아직 열려 있는 날의 마지막 봉은 하루치가 아니므로 예측에서 뺀다. */
	static List<Candle> withoutOpenSessionBar(List<Candle> candles, Market market) {
		if (candles.isEmpty()) {
			return candles;
		}
		java.time.ZonedDateTime now = java.time.ZonedDateTime.now(market.zone());
		Candle last = candles.get(candles.size() - 1);
		if (market.isOpenAt(now) && now.toLocalDate().toString().equals(last.date())) {
			return candles.subList(0, candles.size() - 1);
		}
		return candles;
	}

	private List<Recommendation> recommendLive(MemberAccount account, String group, int size, List<String> universeOverride) {
		boolean us = "US".equalsIgnoreCase(group);
		List<String> universe = universeOverride != null && !universeOverride.isEmpty() ? universeOverride
			: us ? props.getRecommendation().getUsUniverse() : props.getRecommendation().getKrxUniverse();
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
						List<Candle> c = withoutOpenSessionBar(marketData.candles(account, stock.market(), sym, Period.D, CANDLE_COUNT), stock.market());
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
		log.info("추천 후보 시세 수집 완료 group={} 요청={} 성공={}", group, picked.size(), items.size());
		if (items.isEmpty()) {
			log.error("추천 후보 시세를 하나도 가져오지 못했습니다 group={} account={}", group, account.getId());
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
					s.reasons() == null ? List.of() : s.reasons(), null));
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
