package com.backend.domains.agent.skill;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.backend.common.exception.ApiException;
import com.backend.domains.agent.skill.NewsCandidates.Candidate;
import com.backend.domains.agent.skill.NewsDecisionParser.Pick;
import com.backend.domains.member.domain.MemberAccount;
import com.backend.domains.screening.ScreeningService;
import com.backend.market.Market;
import com.backend.market.MarketDataService;
import com.backend.market.dto.MarketDtos.Quote;
import com.backend.market.dto.MarketDtos.StockSummary;

/**
 * AI 가 자유롭게 제안한 종목이 정말 살 만한 종목인지 코드가 확인한다. 통과하지 못하면 사지 않는다.
 * <ol>
 *   <li>종목 마스터에 있는 한국/미국 종목이어야 한다(없는 코드·다른 시장 거부).</li>
 *   <li>AI 가 함께 쓴 종목명이 실제 종목명과 맞아야 한다(코드를 잘못 기억한 경우 거부). 산업 ETF 참고 목록은 예외.</li>
 *   <li>거래대금 상위 종목(야간 분석 대상)이어야 한다. 야간 분석 자료가 없으면 설정의 기본 대형주 목록만 허용한다. 산업 ETF 참고 목록은 예외.</li>
 *   <li>시세가 조회돼야 하고, 오늘 이미 기준 이상 급등한 종목은 뒤늦게 쫓지 않는다.</li>
 * </ol>
 */
final class NewsPickValidator {

	record Result(Candidate candidate, String rejectReason) {
		boolean ok() {
			return candidate != null;
		}
	}

	private final MarketDataService md;
	private final MemberAccount account;
	private final ScreeningService screening;
	private final Set<String> fallbackUniverse = new HashSet<>();
	private final boolean krx;
	private final double chaseLimitPct;
	private final boolean screenFresh;

	NewsPickValidator(MarketDataService md, MemberAccount account, ScreeningService screening, List<String> fallbackUniverse, boolean krx,
		double chaseLimitPct) {
		this.md = md;
		this.account = account;
		this.screening = screening;
		this.krx = krx;
		this.chaseLimitPct = chaseLimitPct;
		if (fallbackUniverse != null) {
			fallbackUniverse.forEach(s -> this.fallbackUniverse.add(s.trim().toUpperCase(Locale.ROOT)));
		}
		this.screenFresh = screening != null && screening.freshDate(group()).isPresent();
	}

	/** 야간 분석 자료가 없어 기본 대형주 목록으로만 허용하는 중이면 true */
	boolean usingFallback() {
		return !screenFresh;
	}

	private String group() {
		return krx ? ScreeningService.KRX : ScreeningService.US;
	}

	Result check(Pick pick) {
		StockSummary s;
		try {
			s = md.resolve(pick.symbol(), krx ? Market.KRX : null);
		} catch (ApiException e) {
			return no("종목 마스터에 없는 코드입니다");
		}
		if (krx != (s.market() == Market.KRX)) {
			return no((krx ? "한국" : "미국") + " 시장 종목이 아닙니다");
		}
		if (krx && s.name() != null && s.name().equals(s.productNumber())) {
			return no("종목 마스터에 없는 코드입니다");
		}
		String symbol = s.productNumber().toUpperCase(Locale.ROOT);
		boolean referenceEtf = NewsCandidates.isReferenceEtf(krx, symbol);
		if (!referenceEtf) {
			if (!nameMatches(pick.name(), s.name(), s.nameEn())) {
				return no("종목명이 코드와 맞지 않습니다 (AI: " + (pick.name().isBlank() ? "없음" : pick.name()) + ", 실제: " + s.name() + ")");
			}
			if (screenFresh) {
				if (screening.lookup(group(), symbol).isEmpty()) {
					return no("거래대금 상위 종목이 아닙니다");
				}
			} else if (!fallbackUniverse.contains(symbol)) {
				return no("야간 분석 자료가 없어 기본 대형주 목록만 허용 중입니다");
			}
		}
		Quote q;
		try {
			q = md.quote(account, s.market(), s.productNumber());
		} catch (ApiException e) {
			return no("시세를 조회하지 못했습니다");
		}
		if (q == null || q.price() == null || q.price().signum() <= 0) {
			return no("시세를 조회하지 못했습니다");
		}
		if (chaseLimitPct > 0 && q.changeRate() >= chaseLimitPct) {
			return no(String.format(Locale.ROOT, "오늘 이미 %+.1f%% 올라 뒤늦게 쫓지 않습니다", q.changeRate()));
		}
		return new Result(new Candidate(symbol, s.market(), s.name(), referenceEtf ? "ETF" : "종목", ""), null);
	}

	private static Result no(String reason) {
		return new Result(null, reason);
	}

	/** 공백·기호·대소문자를 무시하고, 한쪽이 다른 쪽에 들어 있으면(3글자 이상) 같은 종목명으로 본다. */
	static boolean nameMatches(String given, String... actual) {
		String g = norm(given);
		if (g.length() < 2) {
			return false;
		}
		for (String a : actual) {
			String n = norm(a);
			if (n.length() < 2) {
				continue;
			}
			if (n.equals(g)) {
				return true;
			}
			String shorter = g.length() <= n.length() ? g : n;
			String longer = shorter == g ? n : g;
			if (shorter.length() >= 3 && longer.contains(shorter)) {
				return true;
			}
		}
		return false;
	}

	private static String norm(String s) {
		return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
	}
}
