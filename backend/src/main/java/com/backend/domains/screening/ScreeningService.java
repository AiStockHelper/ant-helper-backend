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
				i.direction(), i.confidence(), json.write(i.reasons() == null ? List.of() : i.reasons()), i.avgTradeValue()));
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

	/** 방향과 상관없이 점수가 높은 순 — AI 의견을 만들 때 참고하는 후보 풀 */
	public List<Recommendation> topByScore(String group, int size) {
		return freshDate(group).map(d -> repo.findByMarketGroupAndTradeDateOrderByScoreDesc(group, d, PageRequest.of(0, size))
			.stream().map(this::toRecommendation).toList()).orElse(List.of());
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
