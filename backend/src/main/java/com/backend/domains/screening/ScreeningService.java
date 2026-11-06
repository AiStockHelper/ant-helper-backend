package com.backend.domains.screening;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.backend.common.logging.ErrorEventRecorder;
import com.backend.common.properties.AppProperties;
import com.backend.common.util.Json;
import com.backend.infra.prediction.PredictionClient;
import com.backend.infra.prediction.PredictionClient.BatchItemOut;
import com.backend.infra.prediction.PredictionClient.BatchResultOut;
import com.backend.infra.prediction.PredictionClient.BatchStartRequest;
import com.backend.infra.prediction.PredictionClient.BatchStatusOut;
import com.backend.infra.prediction.PredictionClient.BatchSymbolIn;
import com.backend.market.Currency;
import com.backend.market.Market;
import com.backend.market.dto.MarketDtos.Recommendation;
import com.backend.market.stock.StockMaster;
import com.backend.market.stock.StockMasterRepository;
import com.fasterxml.jackson.core.type.TypeReference;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 야간 스크리닝. 종목 마스터의 전체 종목을 예측 서버에 넘겨 일봉 수집·스코어링을 시키고(pykrx/yfinance),
 * 결과를 DB 에 저장한다. 추천은 이 저장된 결과를 읽으므로 요청 시점에 증권사 API 를 부르지 않고 즉시 응답한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScreeningService {

	public static final String KRX = "KRX";
	public static final String US = "US";

	public record Ranked(DailyScreen row, long rank, long total) {
	}

	public record GroupStatus(String market, LocalDate latestTradeDate, long count, boolean running, boolean fresh) {
	}

	private final PredictionClient client;
	private final StockMasterRepository masters;
	private final DailyScreenRepository repo;
	private final AppProperties props;
	private final TransactionTemplate tx;
	private final Json json;
	private final ErrorEventRecorder recorder;

	private final Map<String, AtomicBoolean> running = new ConcurrentHashMap<>();

	public static String groupOf(Market market) {
		return market == Market.KRX ? KRX : US;
	}

	public static String normalizeGroup(String value) {
		if (value == null) {
			return KRX;
		}
		String v = value.trim().toUpperCase(Locale.ROOT);
		return v.equals("US") || v.equals("NASDAQ") || v.equals("NYSE") || v.equals("AMEX") ? US : KRX;
	}

	private static Market zoneMarket(String group) {
		return KRX.equals(group) ? Market.KRX : Market.NASDAQ;
	}

	public boolean isRunning(String group) {
		AtomicBoolean f = running.get(group);
		return f != null && f.get();
	}

	// ─────────────────────────── 배치 실행 ───────────────────────────

	/** 배치를 끝까지 실행한다(수십 분 걸릴 수 있으므로 호출자가 별도 스레드를 쓸 것). 이미 돌고 있으면 false. */
	public boolean runBlocking(String group) {
		if (!props.getScreening().isEnabled()) {
			log.info("스크리닝이 설정으로 꺼져 있습니다.");
			return false;
		}
		AtomicBoolean flag = running.computeIfAbsent(group, k -> new AtomicBoolean());
		if (!flag.compareAndSet(false, true)) {
			log.info("[{}] 스크리닝이 이미 실행 중입니다.", group);
			return false;
		}
		long started = System.currentTimeMillis();
		try {
			List<BatchSymbolIn> universe = universe(group);
			int top = KRX.equals(group) ? props.getScreening().getTopLiquidKrx() : props.getScreening().getTopLiquidUs();
			log.info("[{}] 스크리닝 시작: 대상 {}종목 중 유동성 상위 {}개 분석", group, universe.size(), top);
			BatchStatusOut st = client.startBatch(new BatchStartRequest(group, universe, top));
			long deadline = System.currentTimeMillis() + Duration.ofMinutes(props.getScreening().getTimeoutMinutes()).toMillis();
			String lastPhase = "";
			while ("RUNNING".equals(st.status())) {
				if (System.currentTimeMillis() > deadline) {
					throw new IllegalStateException("배치가 제한 시간(" + props.getScreening().getTimeoutMinutes() + "분)을 넘었습니다.");
				}
				Thread.sleep(15_000);
				st = client.batchStatus(st.jobId());
				String now = st.phase() + " " + st.done() + "/" + st.total();
				if (!now.equals(lastPhase)) {
					log.info("[{}] 스크리닝 진행: {}", group, now);
					lastPhase = now;
				}
			}
			if (!"DONE".equals(st.status())) {
				throw new IllegalStateException("예측 서버 배치 실패: " + st.error());
			}
			int saved = save(group, client.batchResult(st.jobId()));
			log.info("[{}] 스크리닝 완료: {}종목 저장, {}초", group, saved, (System.currentTimeMillis() - started) / 1000);
			return true;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		} catch (RuntimeException e) {
			log.error("[{}] 스크리닝 실패: {}", group, e.toString());
			recorder.record("SCREENING", null, "SCREENING_FAILED", e.getMessage(), e, "market=" + group);
			return false;
		} finally {
			flag.set(false);
		}
	}

	public void runAsync(String group) {
		Thread.ofVirtual().name("screening-" + group).start(() -> runBlocking(group));
	}

	/** 분석 대상: 보통주만(ETF·우선주·스팩·워런트 제외). 유동성 필터는 예측 서버가 데이터를 보고 한다. */
	List<BatchSymbolIn> universe(String group) {
		boolean krx = KRX.equals(group);
		List<Market> markets = krx ? List.of(Market.KRX) : List.of(Market.NASDAQ, Market.NYSE, Market.AMEX);
		Map<String, BatchSymbolIn> out = new LinkedHashMap<>();
		for (StockMaster s : masters.findByMarketInAndSecurityType(markets, "ST")) {
			String sym = s.getSymbol();
			String name = s.getName() == null ? "" : s.getName();
			String en = s.getNameEn() == null ? "" : s.getNameEn().toUpperCase(Locale.ROOT);
			if (krx) {
				// 보통주 코드는 0 으로 끝난다(우선주는 5, 7 등).
				if (!sym.matches("[0-9A-Z]{6}") || !sym.endsWith("0") || name.contains("스팩")) {
					continue;
				}
			} else if (!sym.matches("[A-Z]{1,5}") || en.contains("WARRANT") || en.contains(" RIGHT") || en.endsWith(" UNIT")
				|| en.contains(" UNITS") || en.contains("PREFERRED") || en.contains(" PFD") || name.contains("우선주")) {
				continue;
			}
			out.putIfAbsent(sym, new BatchSymbolIn(sym, name, s.getMarket().name()));
		}
		return new ArrayList<>(out.values());
	}

	private int save(String group, BatchResultOut result) {
		if (result == null || result.items() == null || result.items().isEmpty()) {
			throw new IllegalStateException("분석 결과가 비어 있습니다.");
		}
		LocalDate date = LocalDate.parse(result.status().tradeDate());
		Market fallback = zoneMarket(group);
		List<DailyScreen> rows = new ArrayList<>();
		for (BatchItemOut i : result.items()) {
			Market m = fallback;
			try {
				if (i.market() != null) {
					m = Market.parse(i.market());
				}
			} catch (RuntimeException ignored) {
				// 알 수 없는 거래소면 그룹 기본 시장으로 둔다.
			}
			BigDecimal price = m.currency().round(BigDecimal.valueOf(i.currentPrice()));
			rows.add(new DailyScreen(date, group, m, i.symbol(), i.name(), price, i.score(), i.predictedChangeRate(),
				i.direction(), i.confidence(), json.write(i.reasons() == null ? List.of() : i.reasons()), i.avgTradeValue(),
				i.facts() == null || i.facts().isEmpty() ? null : json.write(i.facts())));
		}
		tx.executeWithoutResult(s -> {
			repo.deleteDay(group, date);
			repo.saveAll(rows);
			repo.deleteOlderThan(LocalDate.now().minusDays(props.getScreening().getKeepDays()));
		});
		return rows.size();
	}

	// ─────────────────────────── 조회 ───────────────────────────

	/** 사용할 수 있는(너무 오래되지 않은) 가장 최근 분석일 */
	public Optional<LocalDate> freshDate(String group) {
		if (!props.getScreening().isEnabled()) {
			return Optional.empty();
		}
		return repo.latestTradeDate(group).filter(d -> !d.isBefore(LocalDate.now(zoneMarket(group).zone())
			.minusDays(props.getScreening().getMaxAgeDays())));
	}

	/** 상승이 예상되는 종목(점수순) */
	public List<Recommendation> risers(String group, int size) {
		return freshDate(group).map(d -> repo.findRisers(group, d, PageRequest.of(0, size)).stream()
			.map(this::toRecommendation).toList()).orElse(List.of());
	}

	/** 모델 추천: 요인 점수가 평균보다 확실히 높은 종목(점수순). 모델의 방향 예측은 참고값일 뿐 조건이 아니다. */
	public List<Recommendation> picks(String group, int size) {
		return freshDate(group).map(d -> repo.findPicks(group, d, MIN_PICK_SCORE, PageRequest.of(0, size)).stream()
			.map(this::toRecommendation).toList()).orElse(List.of());
	}

	public static final double MIN_PICK_SCORE = 10.0;

	/** AI 가 직접 훑어보는 시장 개요의 한 줄. 모델 점수·방향은 일부러 넣지 않는다. */
	public record ScanRow(String productNumber, String name, Market market, Currency currency, BigDecimal price,
						  double avgTradeValue, Map<String, Double> facts) {
	}

	public enum ScanView {
		LIQUID("거래대금이 큰 순"),
		STABLE("20일 변동성이 낮은 순"),
		VOLATILE("20일 변동성이 높은 순"),
		OVERSOLD("RSI가 낮은(과매도) 순"),
		OVERBOUGHT("RSI가 높은(과열) 순"),
		DIP("최근 5일 수익률이 낮은(눌린) 순"),
		RALLY("최근 5일 수익률이 높은 순"),
		MOMENTUM("60일 수익률이 높은 순"),
		VOLUME_SURGE("거래량이 5일 평균보다 급증한 순"),
		NEAR_HIGH("60일 고점에 가까운 순");

		private final String label;

		ScanView(String label) {
			this.label = label;
		}

		public String label() {
			return label;
		}

		public static ScanView parse(String v) {
			if (v == null || v.isBlank()) {
				return LIQUID;
			}
			try {
				return valueOf(v.trim().toUpperCase(Locale.ROOT));
			} catch (IllegalArgumentException e) {
				return LIQUID;
			}
		}
	}

	/**
	 * 거래대금 상위 종목(추천 대상과 같은 범위)을 요인 값으로 정렬해 보여 준다.
	 * 모델 점수는 노출하지 않으므로 AI 는 이 숫자들로 스스로 판단해야 한다.
	 */
	public List<ScanRow> scan(String group, ScanView view, int size) {
		LocalDate date = freshDate(group).orElse(null);
		if (date == null) {
			return List.of();
		}
		List<ScanRow> pool = repo.findByMarketGroupAndTradeDateOrderByAvgTradeValueDesc(group, date,
				PageRequest.of(0, props.getScreening().getScanPool())).stream()
			.map(d -> new ScanRow(d.getSymbol(), d.getName(), d.getMarket(), d.getMarket().currency(), d.getPrice(),
				d.getAvgTradeValue() == null ? 0 : d.getAvgTradeValue(),
				json.read(d.getFactsJson(), new TypeReference<Map<String, Double>>() {}, Map.of())))
			.toList();
		java.util.Comparator<ScanRow> order = switch (view) {
			case LIQUID -> java.util.Comparator.comparingDouble(ScanRow::avgTradeValue).reversed();
			case STABLE -> byFact("vol20", true);
			case VOLATILE -> byFact("vol20", false);
			case OVERSOLD -> byFact("rsi", true);
			case OVERBOUGHT -> byFact("rsi", false);
			case DIP -> byFact("ret5", true);
			case RALLY -> byFact("ret5", false);
			case MOMENTUM -> byFact("mom60", false);
			case VOLUME_SURGE -> byFact("volRatio", false);
			case NEAR_HIGH -> byFact("distHigh60", false);
		};
		return pool.stream().filter(r -> view == ScanView.LIQUID || !r.facts().isEmpty()).sorted(order)
			.limit(Math.max(1, Math.min(size, 40))).toList();
	}

	private static java.util.Comparator<ScanRow> byFact(String key, boolean ascending) {
		java.util.Comparator<ScanRow> c = java.util.Comparator.comparingDouble(
			r -> r.facts().getOrDefault(key, ascending ? Double.MAX_VALUE : -Double.MAX_VALUE));
		return ascending ? c : c.reversed();
	}

	public Optional<Ranked> lookup(String group, String symbol) {
		return freshDate(group).flatMap(d -> repo.findByMarketGroupAndTradeDateAndSymbol(group, d, symbol.toUpperCase(Locale.ROOT))
			.map(row -> new Ranked(row, repo.countByMarketGroupAndTradeDateAndScoreGreaterThan(group, d, row.getScore()) + 1,
				repo.countByMarketGroupAndTradeDate(group, d))));
	}

	public GroupStatus status(String group) {
		Optional<LocalDate> latest = repo.latestTradeDate(group);
		return new GroupStatus(group, latest.orElse(null), latest.map(d -> repo.countByMarketGroupAndTradeDate(group, d)).orElse(0L),
			isRunning(group), freshDate(group).isPresent());
	}

	/** 가장 최근 분석이 순위 근거 값(facts)을 담은 새 방식으로 저장돼 있는가 */
	public boolean hasFacts(String group) {
		return repo.latestTradeDate(group).map(d -> repo.countByMarketGroupAndTradeDateAndFactsJsonIsNotNull(group, d) > 0).orElse(false);
	}

	public boolean hasAny(String group) {
		return repo.latestTradeDate(group).isPresent();
	}

	public Recommendation toRecommendation(DailyScreen d) {
		List<String> reasons = json.read(d.getReasonsJson(), new TypeReference<List<String>>() {}, List.of());
		Currency cur = d.getMarket().currency();
		return new Recommendation(d.getSymbol(), d.getName(), d.getMarket(), cur, d.getPrice(), d.getScore(),
			d.getPredictedChangeRate(), d.getDirection(), d.getConfidence(), reasons, d.getTradeDate().toString());
	}
}
