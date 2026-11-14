package com.backend.domains.agent.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.backend.common.exception.ApiException;
import com.backend.common.exception.ErrorCode;
import com.backend.domains.agent.skill.NewsDecisionParser.Pick;
import com.backend.domains.agent.skill.NewsPickValidator.Result;
import com.backend.domains.member.domain.MemberAccount;
import com.backend.domains.screening.ScreeningService;
import com.backend.domains.screening.ScreeningService.Ranked;
import com.backend.market.Currency;
import com.backend.market.Market;
import com.backend.market.MarketDataService;
import com.backend.market.dto.MarketDtos.Quote;
import com.backend.market.dto.MarketDtos.StockSummary;

class NewsPickValidatorTest {

	private MarketDataService md;
	private ScreeningService screening;
	private MemberAccount account;

	@BeforeEach
	void setUp() {
		md = mock(MarketDataService.class);
		screening = mock(ScreeningService.class);
		account = mock(MemberAccount.class);
		when(screening.freshDate(anyString())).thenReturn(Optional.of(LocalDate.of(2026, 11, 9)));
		when(screening.lookup(anyString(), anyString())).thenReturn(Optional.empty());
		when(md.resolve(anyString(), any())).thenAnswer(inv -> {
			String s = inv.getArgument(0);
			return switch (s) {
				case "AAPL" -> new StockSummary("AAPL", "애플", "Apple Inc", Market.NASDAQ, Currency.USD);
				case "XLK" -> new StockSummary("XLK", "엑스엘케이", "Technology Select Sector SPDR Fund", Market.AMEX, Currency.USD);
				case "SQQQ" -> new StockSummary("SQQQ", "인버스", "ProShares UltraPro Short QQQ", Market.NASDAQ, Currency.USD);
				case "005930" -> new StockSummary("005930", "삼성전자", null, Market.KRX, Currency.KRW);
				case "123456" -> new StockSummary("123456", "123456", null, Market.KRX, Currency.KRW);              // 종목 마스터에 없는 6자리 코드
				default -> throw ApiException.of(ErrorCode.STOCK_NOT_FOUND, "없음");
			};
		});
		quote(100, 1.0);
	}

	private void quote(double price, double changeRate) {
		when(md.quote(any(), any(), anyString())).thenAnswer(inv -> new Quote(inv.getArgument(2), "n", inv.getArgument(1), Currency.USD,
			BigDecimal.valueOf(price), BigDecimal.ZERO, changeRate, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0, OffsetDateTime.now()));
	}

	private NewsPickValidator us(double chase) {
		return new NewsPickValidator(md, account, screening, List.of("AAPL"), false, chase);
	}

	private static Pick pick(String symbol, String name) {
		return new Pick(symbol, name, 5, "r");
	}

	@Test
	void acceptsALiquidStockWhoseNameMatches() {
		when(screening.lookup("US", "AAPL")).thenReturn(Optional.of(new Ranked(null, 1, 900)));
		Result r = us(8).check(pick("AAPL", "Apple Inc."));
		assertThat(r.ok()).isTrue();
		assertThat(r.candidate().symbol()).isEqualTo("AAPL");
		assertThat(r.candidate().market()).isEqualTo(Market.NASDAQ);
		assertThat(r.candidate().kind()).isEqualTo("종목");
		assertThat(us(8).usingFallback()).isFalse();
	}

	@Test
	void rejectsUnknownCodesWrongMarketAndUnlistedKoreanCodes() {
		assertThat(us(8).check(pick("ZZZZ", "Nothing")).rejectReason()).contains("종목 마스터에 없는 코드");
		NewsPickValidator kr = new NewsPickValidator(md, account, screening, List.of(), true, 8);
		assertThat(kr.check(pick("123456", "무엇")).rejectReason()).contains("종목 마스터에 없는 코드");     // 6자리 대체 응답은 거부
		when(md.resolve(eq("AAPL"), any())).thenReturn(new StockSummary("AAPL", "애플", "Apple Inc", Market.NASDAQ, Currency.USD));
		assertThat(kr.check(pick("AAPL", "Apple")).rejectReason()).contains("한국 시장 종목이 아닙니다");
		assertThat(us(8).check(pick("005930", "삼성전자")).rejectReason()).contains("미국 시장 종목이 아닙니다");
	}

	@Test
	void rejectsACodeWhoseNameDoesNotMatchWhatTheAiSaid() {
		when(screening.lookup("US", "AAPL")).thenReturn(Optional.of(new Ranked(null, 1, 900)));
		Result r = us(8).check(pick("AAPL", "Microsoft"));
		assertThat(r.ok()).isFalse();
		assertThat(r.rejectReason()).contains("종목명이 코드와 맞지 않습니다").contains("Microsoft").contains("애플");
		assertThat(us(8).check(pick("AAPL", "")).rejectReason()).contains("종목명이 코드와 맞지 않습니다").contains("없음");
	}

	@Test
	void rejectsStocksOutsideTheLiquidScreenedSet() {
		Result r = us(8).check(pick("AAPL", "Apple Inc"));        // lookup 이 비어 있다 = 거래대금 상위가 아님
		assertThat(r.ok()).isFalse();
		assertThat(r.rejectReason()).contains("거래대금 상위 종목이 아닙니다");
	}

	@Test
	void usesTheDefaultLargeCapListWhenThereIsNoFreshNightlyScreen() {
		when(screening.freshDate(anyString())).thenReturn(Optional.empty());
		NewsPickValidator v = us(8);
		assertThat(v.usingFallback()).isTrue();
		assertThat(v.check(pick("AAPL", "Apple Inc")).ok()).isTrue();                       // 설정의 기본 대형주 목록에 있다
		Result other = v.check(pick("SQQQ", "ProShares UltraPro Short QQQ"));
		assertThat(other.ok()).isFalse();
		assertThat(other.rejectReason()).contains("기본 대형주 목록만 허용");
		verify(screening, never()).lookup(anyString(), anyString());
	}

	@Test
	void referenceSectorEtfsSkipTheNameAndLiquidityChecksButNotTheQuote() {
		Result r = us(8).check(pick("XLK", ""));                                              // 이름이 없어도, 야간 분석 대상이 아니어도 통과
		assertThat(r.ok()).isTrue();
		assertThat(r.candidate().kind()).isEqualTo("ETF");
		assertThat(us(8).check(pick("SQQQ", "ProShares UltraPro Short QQQ")).ok()).isFalse();   // 참고 목록 밖 ETF 는 거부
		when(md.quote(any(), any(), eq("XLK"))).thenThrow(ApiException.of(ErrorCode.STOCK_NOT_FOUND, "x"));
		assertThat(us(8).check(pick("XLK", "")).rejectReason()).contains("시세를 조회하지 못했습니다");
	}

	@Test
	void doesNotChaseAStockThatAlreadyJumpedToday() {
		when(screening.lookup("US", "AAPL")).thenReturn(Optional.of(new Ranked(null, 1, 900)));
		quote(100, 12.4);
		Result r = us(8).check(pick("AAPL", "Apple Inc"));
		assertThat(r.ok()).isFalse();
		assertThat(r.rejectReason()).contains("+12.4%").contains("쫓지 않습니다");
		assertThat(us(0).check(pick("AAPL", "Apple Inc")).ok()).isTrue();                        // 0 이면 이 검사를 끈다
		quote(100, 7.9);
		assertThat(us(8).check(pick("AAPL", "Apple Inc")).ok()).isTrue();
	}

	@Test
	void rejectsWhenThePriceIsMissing() {
		when(screening.lookup("US", "AAPL")).thenReturn(Optional.of(new Ranked(null, 1, 900)));
		quote(0, 0);
		assertThat(us(8).check(pick("AAPL", "Apple Inc")).rejectReason()).contains("시세를 조회하지 못했습니다");
	}

	@Test
	void koreanStockIsCheckedAgainstTheKoreanScreen() {
		when(screening.lookup("KRX", "005930")).thenReturn(Optional.of(new Ranked(null, 1, 900)));
		NewsPickValidator kr = new NewsPickValidator(md, account, screening, List.of(), true, 8);
		Result r = kr.check(pick("005930", "삼성전자"));
		assertThat(r.ok()).isTrue();
		assertThat(r.candidate().market()).isEqualTo(Market.KRX);
		verify(screening).freshDate("KRX");
	}

	@Test
	void nameMatchingIgnoresCaseSpacingAndPunctuationButNotUnrelatedNames() {
		assertThat(NewsPickValidator.nameMatches("Apple Inc.", "애플", "APPLE INC")).isTrue();
		assertThat(NewsPickValidator.nameMatches("apple", "애플", "Apple Inc")).isTrue();                  // 일부만 써도 3글자 이상이면 인정
		assertThat(NewsPickValidator.nameMatches("NVIDIA Corporation", "엔비디아", "NVIDIA Corp")).isTrue();
		assertThat(NewsPickValidator.nameMatches("SK 하이닉스", "SK하이닉스", null)).isTrue();
		assertThat(NewsPickValidator.nameMatches("삼성전자", "삼성전자")).isTrue();
		assertThat(NewsPickValidator.nameMatches("Microsoft", "애플", "Apple Inc")).isFalse();
		assertThat(NewsPickValidator.nameMatches("삼성", "삼성전자")).isFalse();                              // 2글자는 부분 일치로 인정하지 않는다
		assertThat(NewsPickValidator.nameMatches("", "삼성전자")).isFalse();
		assertThat(NewsPickValidator.nameMatches(null, "삼성전자")).isFalse();
		assertThat(NewsPickValidator.nameMatches("삼성전자", (String)null)).isFalse();
	}
}
