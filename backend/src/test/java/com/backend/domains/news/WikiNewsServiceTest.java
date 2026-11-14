package com.backend.domains.news;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class WikiNewsServiceTest {

	private static final ObjectMapper JSON = new ObjectMapper();

	private static final String DAY = """
		;Business and economy
		*Chipmaker Foo announces a merger
		**[[Foo Corp|Foo]] buys [[Bar]] for $5 billion.<ref>Reuters</ref>
		**Regulators {{cite web|x}} review the deal.
		*Oil prices surge after a supply cut ([https://example.com Reuters])
		;Armed conflicts and attacks
		*Clashes continue in the north
		;Sports
		*A team wins a trophy
		""";

	private static String page(String title, String content) throws IOException {
		return JSON.writeValueAsString(Map.of("title", title, "revisions", List.of(Map.of("slots", Map.of("main", Map.of("content", content))))));
	}

	private static String response(String... pages) {
		return "{\"query\":{\"pages\":[" + String.join(",", pages) + "]}}";
	}

	@Test
	void wikitextIsReducedToCategoriesAndPlainSentences() {
		Map<String, List<String>> parsed = WikiNewsService.parseWikitext(DAY);
		assertThat(parsed.keySet()).containsExactly("Business and economy", "Armed conflicts and attacks", "Sports");
		assertThat(parsed.get("Business and economy")).containsExactly(
			"Chipmaker Foo announces a merger: Foo buys Bar for $5 billion.",
			"Chipmaker Foo announces a merger: Regulators review the deal.",
			"Oil prices surge after a supply cut");
		assertThat(parsed.get("Armed conflicts and attacks")).containsExactly("Clashes continue in the north");
	}

	@Test
	void pageTitlesAndDatesRoundTrip() {
		assertThat(WikiNewsService.pageTitle(LocalDate.of(2026, 3, 4))).isEqualTo("Portal:Current events/2026 March 4");
		assertThat(WikiNewsService.dateOfTitle("Portal:Current events/2026 March 4")).isEqualTo(LocalDate.of(2026, 3, 4));
		assertThat(WikiNewsService.dateOfTitle("Something else")).isNull();
	}

	@Test
	void digestOrdersCategoriesByPriorityAndSkipsOnesNotListed() {
		Map<LocalDate, Map<String, List<String>>> perDay = Map.of(LocalDate.of(2026, 10, 5), WikiNewsService.parseWikitext(DAY));
		String digest = WikiNewsService.buildDigest(perDay);
		assertThat(digest).startsWith("## Business and economy\n10-05 Chipmaker Foo")
			.contains("\n\n## Armed conflicts and attacks\n10-05 Clashes continue")
			.doesNotContain("Sports").doesNotContain("trophy");
	}

	@Test
	void aBigMonthIsThinnedEvenlyWithinTheCategoryBudget() {
		Map<LocalDate, Map<String, List<String>>> perDay = new java.util.TreeMap<>();
		for (int d = 1; d <= 30; d++) {
			List<String> items = new ArrayList<>();
			for (int i = 0; i < 60; i++) {
				items.add("Item " + d + "-" + i + " " + "x".repeat(150));
			}
			perDay.put(LocalDate.of(2026, 9, d), Map.of("Business and economy", items));
		}
		String digest = WikiNewsService.buildDigest(perDay);
		assertThat(digest.length()).isLessThan(9000 + 40 * 60);
		assertThat(digest).contains("09-01 Item 1-0").contains("09-30 Item 30-59");                // 처음과 끝이 모두 남는다
		assertThat(digest.lines().filter(l -> l.startsWith("09-")).count()).isBetween(30L, 60L);
	}

	@Test
	void monthlyDigestFetchesOnceAndRemembersTheMonth() throws Exception {
		AtomicInteger calls = new AtomicInteger();
		WikiNewsService svc = new WikiNewsService(url -> {
			calls.incrementAndGet();
			assertThat(url).contains("Portal%3ACurrent+events%2F2026+October+1");
			return response(page("Portal:Current events/2026 October 5", DAY));
		});
		String first = svc.monthlyDigest(YearMonth.of(2026, 10));
		assertThat(first).contains("## Business and economy").contains("10-05 Chipmaker Foo");
		assertThat(svc.monthlyDigest(YearMonth.of(2026, 10))).isSameAs(first);
		assertThat(calls.get()).isEqualTo(1);                                                // 31일 = 한 번의 요청(40일씩 묶음)
	}

	@Test
	void failureReturnsNullAndIsRetriedLaterWithoutCachingTheFailure() {
		AtomicInteger calls = new AtomicInteger();
		WikiNewsService svc = new WikiNewsService(url -> {
			if (calls.incrementAndGet() <= 3) {
				throw new IOException("요청 한도(429)");
			}
			return response("{\"title\":\"Portal:Current events/2026 October 5\",\"missing\":true}");
		});
		svc.retryPauseMillis = 0;
		assertThat(svc.monthlyDigest(YearMonth.of(2026, 10))).isNull();                      // 3번 재시도 뒤 포기
		assertThat(calls.get()).isEqualTo(3);
		assertThat(svc.monthlyDigest(YearMonth.of(2026, 10))).isNull();                      // 내용이 비어도 null, 기억하지 않는다
	}
}
