package com.backend.domains.agent.skill;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;

import com.backend.domains.agent.skill.NewsDecisionParser.Decision;

class NewsDecisionParserTest {

	private static final Set<String> HELD = Set.of("XLF", "005930");

	@Test
	void readsAJsonAnswerEvenWhenWrappedInACodeBlock() {
		String text = """
			분석 결과입니다:
			```json
			{"marketView":"기술주 강세, 유가 약세","buys":[{"symbol":"xle","name":"Energy Select Sector SPDR","conviction":3,"reason":"유가 반등"},
			{"symbol":"AAPL","name":"Apple Inc.","conviction":5,"reason":"실적 호조"}],
			 "sells":[{"symbol":"XLF","reason":"금리 부담"}]}
			```
			""";
		Decision d = NewsDecisionParser.parse(text, HELD);
		assertThat(d).isNotNull();
		assertThat(d.marketView()).isEqualTo("기술주 강세, 유가 약세");
		assertThat(d.buys()).extracting(p -> p.symbol() + ":" + p.conviction()).containsExactly("AAPL:5", "XLE:3");   // 확신 높은 순, 대소문자 무시
		assertThat(d.buys().get(0).name()).isEqualTo("Apple Inc.");
		assertThat(d.sells()).singleElement().satisfies(s -> assertThat(s.symbol()).isEqualTo("XLF"));
		assertThat(d.rejected()).isZero();
	}

	@Test
	void anyWellFormedTickerOrCodeIsKeptBecauseExistenceIsCheckedLater() {
		String text = """
			{"buys":[{"symbol":"005380","name":"현대차","conviction":4},{"symbol":"BRK.B","name":"Berkshire","conviction":4},{"symbol":"0080G0","name":"x","conviction":4}]}
			""";
		Decision d = NewsDecisionParser.parse(text, HELD);
		assertThat(d.buys()).extracting(p -> p.symbol()).containsExactly("005380", "BRK.B", "0080G0");
	}

	@Test
	void dropsMalformedSymbolsHeldBuysDuplicatesAndSellsOfStocksNotHeld() {
		String text = """
			{"marketView":"x","buys":[{"symbol":"삼성전자","name":"한글은 코드가 아님","conviction":5},{"symbol":"005930","name":"이미 보유","conviction":5},
			{"symbol":"AAPL","name":"Apple","conviction":4},{"symbol":"AAPL","name":"Apple","conviction":5},{"symbol":"DROP TABLE;","conviction":5},{"symbol":"","conviction":5}],
			"sells":[{"symbol":"XLE","reason":"보유하지 않음"},{"symbol":"","reason":"빈 코드"}]}
			""";
		Decision d = NewsDecisionParser.parse(text, HELD);
		assertThat(d.buys()).extracting(p -> p.symbol()).containsExactly("AAPL");
		assertThat(d.buys().get(0).conviction()).isEqualTo(4);          // 중복은 첫 항목만
		assertThat(d.sells()).isEmpty();
		assertThat(d.rejected()).isEqualTo(7);
	}

	@Test
	void clampsConvictionAndRejectsMissingOrNonsenseValues() {
		String text = """
			{"buys":[{"symbol":"AAPL","name":"Apple","conviction":"5"},{"symbol":"MSFT","name":"Microsoft","conviction":9},{"symbol":"NVDA","name":"NVIDIA"}]}
			""";
		Decision d = NewsDecisionParser.parse(text, Set.of());
		assertThat(d.buys()).extracting(p -> p.symbol() + ":" + p.conviction()).containsExactlyInAnyOrder("AAPL:5", "MSFT:5");
		assertThat(d.rejected()).isEqualTo(1);
		Decision none = NewsDecisionParser.parse("{\"buys\":[{\"symbol\":\"XLK\"},{\"symbol\":\"XLE\",\"conviction\":0}]}", Set.of());
		assertThat(none.buys()).isEmpty();
		assertThat(none.rejected()).isEqualTo(2);
	}

	@Test
	void capsHowManyBuysTheAiCanProposeAtOnce() {
		StringBuilder sb = new StringBuilder("{\"buys\":[");
		for (int i = 0; i < 15; i++) {
			sb.append(i > 0 ? "," : "").append("{\"symbol\":\"T").append(i).append("\",\"name\":\"n\",\"conviction\":4}");
		}
		Decision d = NewsDecisionParser.parse(sb.append("]}").toString(), Set.of());
		assertThat(d.buys()).hasSize(NewsDecisionParser.MAX_BUYS);
		assertThat(d.rejected()).isEqualTo(5);
	}

	@Test
	void aHeldStockCanOnlyBeSoldNotBoughtAgain() {
		String text = "{\"buys\":[{\"symbol\":\"XLF\",\"conviction\":5,\"reason\":\"a\"}],\"sells\":[{\"symbol\":\"XLF\",\"reason\":\"b\"}]}";
		Decision d = NewsDecisionParser.parse(text, HELD);
		assertThat(d.buys()).isEmpty();
		assertThat(d.sells()).hasSize(1);
	}

	@Test
	void returnsNullWhenTheAnswerIsNotJson() {
		assertThat(NewsDecisionParser.parse(null, HELD)).isNull();
		assertThat(NewsDecisionParser.parse("오늘은 쉬겠습니다.", HELD)).isNull();
		assertThat(NewsDecisionParser.parse("{ not json }", HELD)).isNull();
		assertThat(NewsDecisionParser.parse("[1,2,3]", HELD)).isNull();
	}

	@Test
	void emptyBuysAndSellsAreAValidDecisionToDoNothing() {
		Decision d = NewsDecisionParser.parse("{\"marketView\":\"불확실\",\"buys\":[],\"sells\":[]}", HELD);
		assertThat(d).isNotNull();
		assertThat(d.buys()).isEmpty();
		assertThat(d.sells()).isEmpty();
	}
}
