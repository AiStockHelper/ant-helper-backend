package com.backend.domains.agent.skill;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.backend.common.exception.ApiException;
import com.backend.market.Market;
import com.backend.market.MarketDataService;
import com.backend.market.dto.MarketDtos.StockSummary;

/**
 * 산업 ETF 참고 목록. AI 가 종목을 자유롭게 제안하는 방식에서도 '산업 전체'에 투자하고 싶을 때 쓸 수 있도록 프롬프트에 보여 주고,
 * 이 목록의 ETF 는 야간 분석 대상(보통주만)이 아니어도 통과시킨다. 목록의 코드는 사람이 확인한 것이다.
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

	static boolean isReferenceEtf(boolean krx, String symbol) {
		String s = symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
		return (krx ? KRX_ETFS : US_ETFS).stream().anyMatch(e -> e.symbol().equals(s));
	}

	/** 종목 마스터에서 확인된 참고 ETF 만(이름 포함). */
	static List<Candidate> referenceEtfs(MarketDataService md, boolean krx) {
		List<Candidate> out = new ArrayList<>();
		for (Seed s : krx ? KRX_ETFS : US_ETFS) {
			Candidate c = resolve(md, s.symbol(), krx, s.theme());
			if (c != null) {
				out.add(c);
			}
		}
		return out;
	}

	private static Candidate resolve(MarketDataService md, String symbol, boolean krx, String theme) {
		try {
			StockSummary s = md.resolve(symbol, krx ? Market.KRX : null);
			if (krx != (s.market() == Market.KRX)) {
				return null;
			}
			if (krx && s.name() != null && s.name().equals(s.productNumber())) {
				return null;      // 종목 마스터에 없는 6자리 코드는 이름을 알 수 없어 제외한다
			}
			return new Candidate(s.productNumber().toUpperCase(Locale.ROOT), s.market(), s.name(), "ETF", theme);
		} catch (ApiException e) {
			return null;
		}
	}
}
