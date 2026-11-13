package com.backend.domains.agent.skill;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.backend.common.exception.ApiException;
import com.backend.market.Market;
import com.backend.market.MarketDataService;
import com.backend.market.dto.MarketDtos.StockSummary;

/**
 * 뉴스 AI 스킬이 고를 수 있는 후보 목록. AI 는 이 목록 밖의 종목을 살 수 없다(환각·프롬프트 주입 방지).
 * 기본 후보는 산업(섹터) ETF 다: 뉴스 기반 섹터 선택을 과거 데이터로 시험했을 때 쓴 종류이고, 개별 종목보다 분산되어 있다.
 */
final class NewsCandidates {

	private NewsCandidates() {
	}

	record Candidate(String symbol, Market market, String name, String kind, String theme) {
	}

	record Seed(String symbol, String theme) {
	}

	/** 미국 SPDR 섹터 ETF 11종 */
	static final List<Seed> US_ETFS = List.of(
		new Seed("XLK", "기술 (Technology)"), new Seed("XLE", "에너지 (Energy)"), new Seed("XLF", "금융 (Financials)"),
		new Seed("XLV", "헬스케어 (Health Care)"), new Seed("XLY", "경기소비재 (Consumer Discretionary)"),
		new Seed("XLP", "필수소비재 (Consumer Staples)"), new Seed("XLI", "산업재 (Industrials)"),
		new Seed("XLU", "유틸리티 (Utilities)"), new Seed("XLB", "소재 (Materials)"), new Seed("XLRE", "부동산 (Real Estate)"),
		new Seed("XLC", "커뮤니케이션 (Communication Services)"));

	/** 한국 업종 ETF(KODEX/TIGER). 종목 마스터에 없는 코드는 실행 때 자동으로 빠진다. */
	static final List<Seed> KRX_ETFS = List.of(
		new Seed("091160", "반도체"), new Seed("266370", "IT"), new Seed("305720", "2차전지"), new Seed("244580", "바이오"),
		new Seed("266420", "헬스케어"), new Seed("091180", "자동차"), new Seed("091170", "은행"), new Seed("102970", "증권"),
		new Seed("140700", "보험"), new Seed("117700", "건설"), new Seed("117680", "철강"), new Seed("117460", "에너지화학"),
		new Seed("140710", "운송"), new Seed("266390", "경기소비재"), new Seed("266410", "필수소비재"), new Seed("300950", "게임"));

	static final int MAX_STOCKS = 25;

	/**
	 * @param universe "ETF" | "STOCK" | "BOTH"
	 * @param custom   사용자가 직접 지정한 종목(있으면 이것만 쓴다)
	 * @param stocks   기본 대형주 목록(설정의 추천 후보군)
	 */
	static List<Candidate> build(MarketDataService md, boolean krx, String universe, List<String> custom, List<String> stocks) {
		Map<String, Candidate> out = new LinkedHashMap<>();
		if (custom != null && !custom.isEmpty()) {
			for (String s : custom) {
				add(out, resolve(md, s, krx, "종목", ""));
			}
			return new ArrayList<>(out.values());
		}
		if (!"STOCK".equals(universe)) {
			for (Seed s : krx ? KRX_ETFS : US_ETFS) {
				add(out, resolve(md, s.symbol(), krx, "ETF", s.theme()));
			}
		}
		if (!"ETF".equals(universe) && stocks != null) {
			stocks.stream().limit(MAX_STOCKS).forEach(s -> add(out, resolve(md, s, krx, "종목", "")));
		}
		return new ArrayList<>(out.values());
	}

	private static void add(Map<String, Candidate> out, Candidate c) {
		if (c != null) {
			out.putIfAbsent(c.symbol(), c);
		}
	}

	private static Candidate resolve(MarketDataService md, String symbol, boolean krx, String kind, String theme) {
		try {
			StockSummary s = md.resolve(symbol, krx ? Market.KRX : null);
			if (krx != (s.market() == Market.KRX)) {
				return null;
			}
			if (krx && s.name() != null && s.name().equals(s.productNumber())) {
				return null;      // 종목 마스터에 없는 6자리 코드는 이름을 알 수 없어 제외한다
			}
			return new Candidate(s.productNumber().toUpperCase(Locale.ROOT), s.market(), s.name(), kind, theme);
		} catch (ApiException e) {
			return null;
		}
	}
}
