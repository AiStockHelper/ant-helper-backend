package com.backend.domains.agent.skill;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;

import com.backend.domains.agent.skill.NewsDecisionParser.Decision;

class NewsDecisionParserTest {

	private static final Set<String> CANDIDATES = Set.of("XLK", "XLE", "XLF");
	private static final Set<String> HELD = Set.of("XLF");

	@Test
	void readsAJsonAnswerEvenWhenWrappedInACodeBlock() {
		String text = """
			분석 결과입니다:
			```json
			{"marketView":"기술주 강세, 유가 약세","buys":[{"symbol":"xle","conviction":3,"reason":"유가 반등"},{"symbol":"XLK","conviction":5,"reason":"실적 호조"}],
			 "sells":[{"symbol":"XLF","reason":"금리 부담"}]}
			```
			""";
		Decision d = NewsDecisionParser.parse(text, CANDIDATES, HELD);
		assertThat(d).isNotNull();
		assertThat(d.marketView()).isEqualTo("기술주 강세, 유가 약세");
		assertThat(d.buys()).extracting(p -> p.symbol() + ":" + p.conviction()).containsExactly("XLK:5", "XLE:3");   // 확신 높은 순, 대소문자 무시
		assertThat(d.sells()).singleElement().satisfies(s -> assertThat(s.symbol()).isEqualTo("XLF"));
		assertThat(d.rejected()).isZero();
	}

	@Test
	void dropsSymbolsOutsideTheCandidateListAndSellsOfStocksNotHeld() {
		String text = """
			{"marketView":"x","buys":[{"symbol":"NVDA","conviction":5,"reason":"목록 밖"},{"symbol":"XLF","conviction":5,"reason":"이미 보유"},
			{"symbol":"XLK","conviction":4,"reason":"ok"},{"symbol":"XLK","conviction":5,"reason":"중복"}],
			"sells":[{"symbol":"XLE","reason":"보유하지 않음"},{"symbol":"","reason":"빈 코드"}]}
			""";
		Decision d = NewsDecisionParser.parse(text, CANDIDATES, HELD);
		assertThat(d.buys()).extracting(p -> p.symbol()).containsExactly("XLK");
		assertThat(d.buys().get(0).conviction()).isEqualTo(4);          // 중복은 첫 항목만
		assertThat(d.sells()).isEmpty();
		assertThat(d.rejected()).isEqualTo(5);
	}

	@Test
	void clampsConvictionAndRejectsMissingOrNonsenseValues() {
		String text = """
			{"buys":[{"symbol":"XLK","conviction":"5"},{"symbol":"XLE","conviction":9},{"symbol":"XLF2","conviction":3}]}
			""";
		Decision d = NewsDecisionParser.parse(text, Set.of("XLK", "XLE"), Set.of());
		assertThat(d.buys()).extracting(p -> p.symbol() + ":" + p.conviction()).containsExactlyInAnyOrder("XLK:5", "XLE:5");
		assertThat(d.rejected()).isEqualTo(1);
		Decision none = NewsDecisionParser.parse("{\"buys\":[{\"symbol\":\"XLK\"},{\"symbol\":\"XLE\",\"conviction\":0}]}", Set.of("XLK", "XLE"), Set.of());
		assertThat(none.buys()).isEmpty();
		assertThat(none.rejected()).isEqualTo(2);
	}

	@Test
	void aHeldStockCanOnlyBeSoldNotBoughtAgain() {
		String text = "{\"buys\":[{\"symbol\":\"XLF\",\"conviction\":5,\"reason\":\"a\"}],\"sells\":[{\"symbol\":\"XLF\",\"reason\":\"b\"}]}";
		// XLF 는 이미 보유 중이라 매수 의견은 걸러지고 매도 의견만 남는다
		Decision d = NewsDecisionParser.parse(text, CANDIDATES, HELD);
		assertThat(d.buys()).isEmpty();
		assertThat(d.sells()).hasSize(1);
	}

	@Test
	void returnsNullWhenTheAnswerIsNotJson() {
		assertThat(NewsDecisionParser.parse(null, CANDIDATES, HELD)).isNull();
		assertThat(NewsDecisionParser.parse("오늘은 쉬겠습니다.", CANDIDATES, HELD)).isNull();
		assertThat(NewsDecisionParser.parse("{ not json }", CANDIDATES, HELD)).isNull();
		assertThat(NewsDecisionParser.parse("[1,2,3]", CANDIDATES, HELD)).isNull();
	}

	@Test
	void emptyBuysAndSellsAreAValidDecisionToDoNothing() {
		Decision d = NewsDecisionParser.parse("{\"marketView\":\"불확실\",\"buys\":[],\"sells\":[]}", CANDIDATES, HELD);
		assertThat(d).isNotNull();
		assertThat(d.buys()).isEmpty();
		assertThat(d.sells()).isEmpty();
	}
}
