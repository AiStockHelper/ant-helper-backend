package com.backend.market;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.backend.common.exception.ApiException;
import com.backend.common.exception.ErrorCode;
import com.backend.common.properties.AppProperties;
import com.backend.domains.member.domain.MemberAccount;
import com.backend.market.dto.MarketDtos.Candle;
import com.backend.market.dto.MarketDtos.Quote;
import com.backend.market.dto.MarketDtos.StockSummary;
import com.backend.market.stock.StockMaster;
import com.backend.market.stock.StockMasterRepository;
import com.backend.order.kis.kis_api.api.rest.overseas.OverseasQuotationApis.DailyPriceApi;
import com.backend.order.kis.kis_api.api.rest.overseas.OverseasQuotationApis.DailyPriceResult;
import com.backend.order.kis.kis_api.api.rest.overseas.OverseasQuotationApis.PriceDetailApi;
import com.backend.order.kis.kis_api.api.rest.overseas.OverseasQuotationApis.PriceDetailResult;
import com.backend.order.kis.kis_api.api.rest.quotations.InquireDailyItemchartpriceApi;
import com.backend.order.kis.kis_api.api.rest.quotations.InquireDailyItemchartpriceResult;
import com.backend.order.kis.kis_api.api.rest.quotations.InquirePriceApi;
import com.backend.order.kis.kis_api.api.rest.quotations.InquirePriceResult;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarketDataService {

	public enum Period {
		D("D", "0", 100), W("W", "1", 700), M("M", "2", 3000);

		final String krxCode;
		final String usCode;
		final int windowDays;

		Period(String krxCode, String usCode, int windowDays) {
			this.krxCode = krxCode;
			this.usCode = usCode;
			this.windowDays = windowDays;
		}

		public static Period parse(String v) {
			if (v == null) {
				return D;
			}
			return switch (v.trim().toUpperCase(Locale.ROOT)) {
				case "W", "WEEK", "WEEKLY" -> W;
				case "M", "MONTH", "MONTHLY" -> M;
				default -> D;
			};
		}
	}

	private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyyMMdd");
	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	private final KisGateway kis;
	private final StockMasterRepository stockMasterRepository;
	private final AppProperties appProperties;

	private final Map<String, CacheEntry<?>> cache = new ConcurrentHashMap<>();

	private record CacheEntry<T>(T value, long expiresAtMillis) {
	}

	// ─────────────────────────── 종목 검색 / 판별 ───────────────────────────

	public List<StockSummary> search(String query, Market market, int limit) {
		if (query == null || query.isBlank()) {
			return List.of();
		}
		String q = query.trim();
		List<Market> markets = market != null ? List.of(market) : enabledMarkets();
		// 정렬(정확히 일치 > 접두 일치 > 일반 주식 > 짧은 이름)은 쿼리에서 처리한다.
		return stockMasterRepository.search(q, markets, PageRequest.of(0, limit)).stream()
			.map(this::toSummary)
			.toList();
	}

	/** 코드/이름/시장 힌트로 종목을 하나로 확정한다. 모호하면 첫 번째 후보를 사용한다. */
	public StockSummary resolve(String codeOrName, Market hint) {
		if (codeOrName == null || codeOrName.isBlank()) {
			throw ApiException.from(ErrorCode.STOCK_NOT_FOUND);
		}
		String key = codeOrName.trim();
		if (hint != null) {
			Optional<StockMaster> exact = stockMasterRepository.findByMarketAndSymbol(hint, key.toUpperCase(Locale.ROOT));
			if (exact.isPresent()) {
				return toSummary(exact.get());
			}
		}
		if (key.matches("\\d{6}") || key.matches("[0-9A-Z]{6}")) {
			Optional<StockMaster> krx = stockMasterRepository.findByMarketAndSymbol(Market.KRX, key.toUpperCase(Locale.ROOT));
			if (krx.isPresent()) {
				return toSummary(krx.get());
			}
			if (key.matches("\\d{6}")) {
				return new StockSummary(key, key, null, Market.KRX, Currency.KRW);
			}
		}
		List<StockMaster> bySymbol = stockMasterRepository.findBySymbolIgnoreCase(key).stream()
			.filter(s -> enabledMarkets().contains(s.getMarket()))
			.toList();
		if (!bySymbol.isEmpty()) {
			return toSummary(bySymbol.get(0));
		}
		List<StockSummary> byName = search(key, hint, 1);
		if (!byName.isEmpty()) {
			return byName.get(0);
		}
		throw ApiException.of(ErrorCode.STOCK_NOT_FOUND, "'" + key + "' 종목을 찾을 수 없습니다.");
	}

	public String nameOf(Market market, String symbol) {
		return stockMasterRepository.findByMarketAndSymbol(market, symbol).map(StockMaster::getName).orElse(symbol);
	}

	public List<Market> enabledMarkets() {
		List<Market> list = new ArrayList<>();
		for (String m : appProperties.getMarkets().getEnabled()) {
			list.add(Market.parse(m));
		}
		return list;
	}

	public void requireEnabled(Market market) {
		if (!enabledMarkets().contains(market)) {
			throw ApiException.from(ErrorCode.MARKET_NOT_SUPPORTED);
		}
	}

	private StockSummary toSummary(StockMaster s) {
		return new StockSummary(s.getSymbol(), s.getName(), s.getNameEn(), s.getMarket(), s.getMarket().currency());
	}

	// ─────────────────────────── 현재가 ───────────────────────────

	public Quote quote(MemberAccount account, Market market, String symbol) {
		requireEnabled(market);
		return cached("q:" + account.getId() + ":" + market + ":" + symbol, 3_000, () ->
			market.overseas() ? overseasQuote(account, market, symbol) : domesticQuote(account, symbol));
	}

	private Quote domesticQuote(MemberAccount account, String symbol) {
		InquirePriceApi api = new InquirePriceApi(symbol);
		api.setFidCondMrktDivCode("J");
		InquirePriceResult.Output o = kis.execute(account, api).getOutput();
		if (o == null) {
			throw ApiException.from(ErrorCode.STOCK_NOT_FOUND);
		}
		BigDecimal price = dec(o.getStckPrpr());
		BigDecimal change = dec(o.getPrdyVrss()).abs();
		String sign = o.getPrdyVrssSign();
		if ("4".equals(sign) || "5".equals(sign)) {
			change = change.negate();
		}
		double rate = dec(o.getPrdyCtrt()).abs().doubleValue();
		if (change.signum() < 0) {
			rate = -rate;
		} else if (change.signum() == 0) {
			rate = 0;
		}
		return new Quote(symbol, nameOf(Market.KRX, symbol), Market.KRX, Currency.KRW,
			price, change, round2(rate), dec(o.getStckOprc()), dec(o.getStckHgpr()), dec(o.getStckLwpr()),
			dec(o.getAcmlVol()).longValue(), OffsetDateTime.now(SEOUL));
	}

	private Quote overseasQuote(MemberAccount account, Market market, String symbol) {
		PriceDetailResult res = kis.execute(account, new PriceDetailApi(market.quoteCode(), symbol));
		PriceDetailResult.Output o = res.getOutput();
		if (o == null || dec(o.getLast()).signum() == 0) {
			throw ApiException.of(ErrorCode.STOCK_NOT_FOUND, symbol + " 시세를 가져오지 못했습니다.");
		}
		BigDecimal price = dec(o.getLast());
		BigDecimal base = dec(o.getBase());
		BigDecimal change = base.signum() > 0 ? price.subtract(base) : BigDecimal.ZERO;
		double rate = base.signum() > 0 ? change.doubleValue() / base.doubleValue() * 100 : 0;
		return new Quote(symbol, nameOf(market, symbol), market, Currency.USD,
			Currency.USD.round(price), Currency.USD.round(change), round2(rate),
			Currency.USD.round(dec(o.getOpen())), Currency.USD.round(dec(o.getHigh())), Currency.USD.round(dec(o.getLow())),
			dec(o.getTvol()).longValue(), OffsetDateTime.now(ZoneId.of("America/New_York")));
	}

	// ─────────────────────────── 캔들 ───────────────────────────

	/** 오래된 순으로 정렬된 캔들. count 개를 채울 때까지 구간을 나눠 조회한다. */
	public List<Candle> candles(MemberAccount account, Market market, String symbol, Period period, int count) {
		requireEnabled(market);
		int want = Math.max(1, Math.min(count, 500));
		List<Candle> all = cached("c:" + account.getId() + ":" + market + ":" + symbol + ":" + period + ":" + want,
			10 * 60_000L, () -> market.overseas()
				? overseasCandles(account, market, symbol, period, want)
				: domesticCandles(account, symbol, period, want));
		return all;
	}

	private List<Candle> domesticCandles(MemberAccount account, String symbol, Period period, int want) {
		TreeMap<String, Candle> byDate = new TreeMap<>();
		LocalDate end = LocalDate.now(SEOUL);
		for (int i = 0; i < 12 && byDate.size() < want; i++) {
			LocalDate start = end.minusDays(period.windowDays - 1L);
			InquireDailyItemchartpriceApi api = new InquireDailyItemchartpriceApi(symbol, start.format(YMD), end.format(YMD));
			api.setFidCondMrktDivCode("J");
			api.setFidPeriodDivCode(period.krxCode);
			InquireDailyItemchartpriceResult res = kis.execute(account, api);
			boolean any = false;
			if (res.getOutput2() != null) {
				for (InquireDailyItemchartpriceResult.Output2 r : res.getOutput2()) {
					if (r == null || r.getStckBsopDate() == null || r.getStckBsopDate().isBlank()) {
						continue;
					}
					any = true;
					String d = r.getStckBsopDate();
					byDate.put(d, new Candle(iso(d), dec(r.getStckOprc()), dec(r.getStckHgpr()), dec(r.getStckLwpr()),
						dec(r.getStckClpr()), dec(r.getAcmlVol()).longValue()));
				}
			}
			if (!any && i > 0) {
				break;
			}
			end = start.minusDays(1);
		}
		return trim(new ArrayList<>(byDate.values()), want);
	}

	private List<Candle> overseasCandles(MemberAccount account, Market market, String symbol, Period period, int want) {
		TreeMap<String, Candle> byDate = new TreeMap<>();
		String bymd = "";
		for (int i = 0; i < 6 && byDate.size() < want; i++) {
			DailyPriceResult res = kis.execute(account,
				new DailyPriceApi(market.quoteCode(), symbol, period.usCode, bymd));
			if (res.getOutput2() == null || res.getOutput2().length == 0) {
				break;
			}
			String oldest = null;
			for (DailyPriceResult.Row r : res.getOutput2()) {
				if (r == null || r.getXymd() == null || r.getXymd().isBlank()) {
					continue;
				}
				byDate.put(r.getXymd(), new Candle(iso(r.getXymd()),
					Currency.USD.round(dec(r.getOpen())), Currency.USD.round(dec(r.getHigh())),
					Currency.USD.round(dec(r.getLow())), Currency.USD.round(dec(r.getClos())), dec(r.getTvol()).longValue()));
				if (oldest == null || r.getXymd().compareTo(oldest) < 0) {
					oldest = r.getXymd();
				}
			}
			if (oldest == null) {
				break;
			}
			bymd = LocalDate.parse(oldest, YMD).minusDays(1).format(YMD);
		}
		return trim(new ArrayList<>(byDate.values()), want);
	}

	private List<Candle> trim(List<Candle> sortedAsc, int want) {
		if (sortedAsc.size() <= want) {
			return sortedAsc;
		}
		return new ArrayList<>(sortedAsc.subList(sortedAsc.size() - want, sortedAsc.size()));
	}

	// ─────────────────────────── 유틸 ───────────────────────────

	@SuppressWarnings("unchecked")
	private <T> T cached(String key, long ttlMillis, Supplier<T> loader) {
		long now = System.currentTimeMillis();
		CacheEntry<?> e = cache.get(key);
		if (e != null && e.expiresAtMillis() > now) {
			return (T)e.value();
		}
		T value = loader.get();
		cache.put(key, new CacheEntry<>(value, now + ttlMillis));
		if (cache.size() > 2000) {
			cache.entrySet().removeIf(en -> en.getValue().expiresAtMillis() < now);
		}
		return value;
	}

	public static BigDecimal dec(String s) {
		if (s == null) {
			return BigDecimal.ZERO;
		}
		String t = s.trim().replace(",", "");
		if (t.isEmpty()) {
			return BigDecimal.ZERO;
		}
		try {
			return new BigDecimal(t);
		} catch (NumberFormatException e) {
			return BigDecimal.ZERO;
		}
	}

	public static double round2(double v) {
		return Math.round(v * 100.0) / 100.0;
	}

	private static String iso(String yyyymmdd) {
		return yyyymmdd.substring(0, 4) + "-" + yyyymmdd.substring(4, 6) + "-" + yyyymmdd.substring(6, 8);
	}
}
