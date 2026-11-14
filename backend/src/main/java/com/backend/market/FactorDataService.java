package com.backend.market;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;

import com.backend.domains.member.domain.MemberAccount;
import com.backend.order.kis.kis_api.api.rest.quotations.InquireIndexPriceApi;
import com.backend.order.kis.kis_api.api.rest.quotations.InquireIndexPriceResult;
import com.backend.order.kis.kis_api.api.rest.quotations.InquireInvestorApi;
import com.backend.order.kis.kis_api.api.rest.quotations.InquireInvestorResult;
import com.backend.order.kis.kis_api.api.rest.quotations.InquirePriceApi;
import com.backend.order.kis.kis_api.api.rest.quotations.InquirePriceResult;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 멀티팩터 스킬이 쓰는 한국 주식 부가 데이터: 투자자별 수급, 밸류에이션(PER/PBR), 시장 지수 등락률.
 * 모두 한국투자증권 조회 API 를 쓰며(수급·지수는 국내 종목 전용), 하루 단위 데이터라 잠깐 캐시한다.
 * 모의투자 서버는 이 API 중 일부를 지원하지 않을 수 있다. 실패하면 예외를 던지니 호출하는 쪽에서 대비해야 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FactorDataService {

	private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;
	private static final long DAILY_TTL_MILLIS = 20 * 60_000L;
	private static final long INDEX_TTL_MILLIS = 60_000L;

	/** PER/PBR 등. 값이 없으면 null. */
	public record Valuation(Double per, Double pbr, Double eps, Double bps) {
		/** 적자 기업: EPS 가 음수이거나 PER 가 음수. (PER 0 은 '없음'으로 올 수 있어 EPS 로 함께 본다) */
		public boolean loss() {
			return (eps != null && eps < 0) || (per != null && per < 0);
		}
	}

	/** 하루치 투자자별 순매수(거래대금, 백만원 단위). buyTotal 은 개인·외국인·기관 매수 거래대금의 합. */
	public record FlowDay(LocalDate date, double foreignNet, double orgNet, double personNet, double buyTotal) {
	}

	/**
	 * 최근 N거래일 수급 요약.
	 * ratio = (외국인+기관 순매수) / (세 주체 매수 거래대금 합). 양수면 외국인·기관이 사 모으는 쪽이다.
	 * streak 은 가장 최근 거래일부터 연속으로 순매수한 일수(순매도로 끊기면 0).
	 */
	public record FlowSummary(int days, double foreignNet, double orgNet, double ratio, int foreignStreak, int orgStreak) {
		public double combinedNet() {
			return foreignNet + orgNet;
		}
	}

	private record Cached(long expiresAt, Object value) {
	}

	private final MarketDataService marketData;
	private final KisGateway kis;
	private final Map<String, Cached> cache = new ConcurrentHashMap<>();

	// ─────────────────────────── 조회 ───────────────────────────

	public Valuation valuation(MemberAccount account, String symbol) {
		return cached("val:" + symbol, DAILY_TTL_MILLIS, () -> {
			InquirePriceApi api = new InquirePriceApi(symbol);
			api.setFidCondMrktDivCode("J");
			InquirePriceResult.Output o = kis.execute(account, api).getOutput();
			if (o == null) {
				return new Valuation(null, null, null, null);
			}
			return new Valuation(num(o.getPer()), num(o.getPbr()), num(o.getEps()), num(o.getBps()));
		});
	}

	/**
	 * 최근 거래일부터 내려가는 순서의 일별 수급. 장중에는 오늘 행이 잠정치라서 빼고, 확정된 거래일만 돌려준다.
	 */
	public List<FlowDay> flows(MemberAccount account, String symbol, LocalDate today) {
		return cached("flow:" + symbol + ":" + today, DAILY_TTL_MILLIS, () -> {
			InquireInvestorResult res = kis.execute(account, new InquireInvestorApi(symbol));
			return parseFlows(res.getOutput(), today);
		});
	}

	/** 코스피(0001)·코스닥(1001) 등 지수의 오늘 등락률(%). 못 가져오면 예외. */
	public double indexChangePct(MemberAccount account, String indexCode) {
		return cached("idx:" + indexCode, INDEX_TTL_MILLIS, () -> {
			InquireIndexPriceResult.Output o = kis.execute(account, new InquireIndexPriceApi(indexCode)).getOutput();
			Double rate = o == null ? null : num(o.getBstpNmixPrdyCtrt());
			if (rate == null) {
				throw new IllegalStateException("지수 등락률을 가져오지 못했습니다: " + indexCode);
			}
			double abs = Math.abs(rate);
			String sign = o.getPrdyVrssSign();
			return "4".equals(sign) || "5".equals(sign) ? -abs : abs;
		});
	}

	// ─────────────────────────── 계산(순수) ───────────────────────────

	static List<FlowDay> parseFlows(InquireInvestorResult.Output[] rows, LocalDate today) {
		List<FlowDay> out = new ArrayList<>();
		if (rows == null) {
			return out;
		}
		for (InquireInvestorResult.Output r : rows) {
			LocalDate date = date(r.getStckBsopDate());
			Double foreign = num(r.getFrgnNtbyTrPbmn());
			Double org = num(r.getOrgnNtbyTrPbmn());
			Double person = num(r.getPrsnNtbyTrPbmn());
			if (date == null || foreign == null || org == null || person == null || !date.isBefore(today)) {
				continue; // 값이 비었거나(미확정) 오늘 행은 쓰지 않는다
			}
			double buyTotal = nz(num(r.getPrsnShnuTrPbmn())) + nz(num(r.getFrgnShnuTrPbmn())) + nz(num(r.getOrgnShnuTrPbmn()));
			out.add(new FlowDay(date, foreign, org, person, buyTotal));
		}
		out.sort(Comparator.comparing(FlowDay::date).reversed());
		return out;
	}

	/** 최근 {@code days} 거래일의 수급을 요약한다. 데이터가 없으면 null. */
	public static FlowSummary summarize(List<FlowDay> newestFirst, int days) {
		if (newestFirst == null || newestFirst.isEmpty() || days <= 0) {
			return null;
		}
		List<FlowDay> window = newestFirst.subList(0, Math.min(days, newestFirst.size()));
		double foreign = 0;
		double org = 0;
		double buy = 0;
		for (FlowDay d : window) {
			foreign += d.foreignNet();
			org += d.orgNet();
			buy += d.buyTotal();
		}
		double ratio = buy > 0 ? (foreign + org) / buy : 0;
		return new FlowSummary(window.size(), foreign, org, ratio, streak(window, true), streak(window, false));
	}

	private static int streak(List<FlowDay> window, boolean foreign) {
		int n = 0;
		for (FlowDay d : window) {
			double v = foreign ? d.foreignNet() : d.orgNet();
			if (v > 0) {
				n++;
			} else {
				break;
			}
		}
		return n;
	}

	// ─────────────────────────── 보조 ───────────────────────────

	@SuppressWarnings("unchecked")
	private <T> T cached(String key, long ttlMillis, Supplier<T> loader) {
		long now = System.currentTimeMillis();
		Cached hit = cache.get(key);
		if (hit != null && hit.expiresAt() > now) {
			return (T)hit.value();
		}
		T value = loader.get();
		cache.put(key, new Cached(now + ttlMillis, value));
		if (cache.size() > 2_000) {
			cache.entrySet().removeIf(e -> e.getValue().expiresAt() <= now);
		}
		return value;
	}

	private static Double num(String s) {
		if (s == null || s.isBlank()) {
			return null;
		}
		try {
			double v = Double.parseDouble(s.replace(",", "").trim());
			return Double.isFinite(v) ? v : null;
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static double nz(Double v) {
		return v == null ? 0 : v;
	}

	private static LocalDate date(String s) {
		try {
			return s == null || s.isBlank() ? null : LocalDate.parse(s.trim(), YMD);
		} catch (RuntimeException e) {
			return null;
		}
	}
}
