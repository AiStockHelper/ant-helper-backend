package com.backend.domains.news;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.backend.domains.news.NewsFeedService.Feed;

class NewsFeedServiceTest {

	private static final Instant NOW = Instant.parse("2026-11-13T12:00:00Z");

	private static byte[] rss(String... items) {
		return ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><rss version=\"2.0\"><channel><title>t</title>" + String.join("", items)
			+ "</channel></rss>").getBytes(StandardCharsets.UTF_8);
	}

	private static String item(String title, String pubDate, String description) {
		return "<item><title>" + title + "</title><link>http://x/" + title.hashCode() + "</link><pubDate>" + pubDate + "</pubDate><description><![CDATA["
			+ description + "]]></description></item>";
	}

	@Test
	void parsesRssItemsInBothRfc1123AndColonOffsetFormats() throws Exception {
		List<NewsItem> items = NewsFeedService.parse(rss(
			item("코스피 상승 마감", "Fri, 13 Nov 2026 21:39:06 +0900", "<p>외국인 <b>순매수</b>&nbsp;확대</p>"),
			item("Fed holds rates", "Fri, 13 Nov 2026 22:36:44 +09:00", "Policy makers kept rates unchanged."),
			item("Oil jumps", "Fri, 13 Nov 2026 12:15:00 GMT", "Fri, 13 Nov")), "테스트");
		assertThat(items).hasSize(3);
		assertThat(items.get(0).title()).isEqualTo("코스피 상승 마감");
		assertThat(items.get(0).summary()).isEqualTo("외국인 순매수 확대");                         // 태그·엔티티 제거
		assertThat(items.get(0).publishedAt()).isEqualTo(Instant.parse("2026-11-13T12:39:06Z"));
		assertThat(items.get(1).publishedAt()).isEqualTo(Instant.parse("2026-11-13T13:36:44Z"));
		assertThat(items.get(2).publishedAt()).isEqualTo(Instant.parse("2026-11-13T12:15:00Z"));
		assertThat(items.get(0).source()).isEqualTo("테스트");
	}

	@Test
	void dropsItemsWithoutTitleOrReadableDateAndRepeatedTitleSummaries() throws Exception {
		List<NewsItem> items = NewsFeedService.parse(rss(
			item("", "Fri, 13 Nov 2026 12:15:00 GMT", "x"),
			item("No date", "not a date", "x"),
			item("Same as summary", "Fri, 13 Nov 2026 12:15:00 GMT", "Same as summary  Reuters")), "s");
		assertThat(items).hasSize(1);
		assertThat(items.get(0).summary()).isEmpty();                                   // 요약이 제목의 반복이면 지운다
	}

	@Test
	void readsAtomEntries() throws Exception {
		String atom = "<?xml version=\"1.0\"?><feed xmlns=\"http://www.w3.org/2005/Atom\"><entry><title>Atom title</title>"
			+ "<updated>2026-11-13T10:00:00Z</updated><summary>Atom summary</summary></entry></feed>";
		List<NewsItem> items = NewsFeedService.parse(atom.getBytes(StandardCharsets.UTF_8), "atom");
		assertThat(items).singleElement().satisfies(i -> {
			assertThat(i.title()).isEqualTo("Atom title");
			assertThat(i.publishedAt()).isEqualTo(Instant.parse("2026-11-13T10:00:00Z"));
		});
	}

	@Test
	void refusesXmlWithDoctypeToBlockExternalEntities() {
		String evil = "<?xml version=\"1.0\"?><!DOCTYPE rss [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><rss><channel><item><title>&x;</title></item></channel></rss>";
		assertThatThrownBy(() -> NewsFeedService.parse(evil.getBytes(StandardCharsets.UTF_8), "evil")).isInstanceOf(Exception.class);
	}

	@Test
	void mergeKeepsRecentItemsRemovesDuplicatesAndInterleavesSources() {
		NewsItem a1 = new NewsItem("A", "Stocks rally on earnings", "", NOW.minus(Duration.ofHours(1)));
		NewsItem a2 = new NewsItem("A", "Oil falls", "", NOW.minus(Duration.ofHours(2)));
		NewsItem a3 = new NewsItem("A", "Chip stocks surge", "", NOW.minus(Duration.ofHours(3)));
		NewsItem dup = new NewsItem("B", "Stocks rally on earnings!", "", NOW.minus(Duration.ofHours(1)));       // 구두점만 다른 같은 제목
		NewsItem b1 = new NewsItem("B", "Fed minutes due", "", NOW.minus(Duration.ofHours(4)));
		NewsItem old = new NewsItem("B", "Ancient news", "", NOW.minus(Duration.ofHours(60)));
		NewsItem future = new NewsItem("B", "From the future", "", NOW.plus(Duration.ofHours(9)));
		List<NewsItem> out = NewsFeedService.merge(List.of(List.of(a1, a2, a3), List.of(dup, b1, old, future)), NOW, Duration.ofHours(30), 4);
		assertThat(out).hasSize(4).doesNotContain(old, future, dup);
		assertThat(out).contains(a1, b1);                                    // 한 출처가 몰아서 차지하지 않는다
		assertThat(out).isSortedAccordingTo((x, y) -> y.publishedAt().compareTo(x.publishedAt()));
		assertThat(NewsFeedService.merge(List.of(List.of(a1, a2, a3)), NOW, Duration.ofHours(30), 2)).containsExactly(a1, a2);
	}

	@Test
	void aFailingFeedDoesNotStopTheOthersAndResultsAreCached() {
		AtomicInteger calls = new AtomicInteger();
		Map<String, byte[]> bodies = new HashMap<>();
		bodies.put("http://ok", rss(item("Good news", "Fri, 13 Nov 2026 11:00:00 GMT", "s")));
		NewsFeedService svc = new NewsFeedService(url -> {
			calls.incrementAndGet();
			byte[] b = bodies.get(url);
			if (b == null) {
				throw new IOException("HTTP 500");
			}
			return b;
		}, Clock.fixed(NOW, ZoneOffset.UTC), "OK|http://ok;BAD|http://bad", "");

		List<NewsItem> first = svc.recent("KRX", Duration.ofHours(30), 10);
		assertThat(first).extracting(NewsItem::title).containsExactly("Good news");
		int afterFirst = calls.get();
		assertThat(afterFirst).isEqualTo(2);
		svc.recent("KRX", Duration.ofHours(30), 10);                            // 캐시: 다시 받지 않는다
		assertThat(calls.get()).isEqualTo(afterFirst);
		assertThat(svc.feedNames("KRX")).containsExactly("OK", "BAD");
	}

	@Test
	void feedOverridesAreParsedAndFallBackToDefaultsWhenInvalid() {
		assertThat(NewsFeedService.parseFeeds("A|https://a.example/rss ; B|https://b.example/feed", List.of()))
			.containsExactly(new Feed("A", "https://a.example/rss"), new Feed("B", "https://b.example/feed"));
		List<Feed> defaults = List.of(new Feed("D", "https://d.example"));
		assertThat(NewsFeedService.parseFeeds("", defaults)).isEqualTo(defaults);
		assertThat(NewsFeedService.parseFeeds("garbage; x|ftp://nope", defaults)).isEqualTo(defaults);
		assertThat(NewsFeedService.DEFAULT_KRX).isNotEmpty().allSatisfy(f -> assertThat(f.url()).startsWith("https://"));
		assertThat(NewsFeedService.DEFAULT_US).isNotEmpty().allSatisfy(f -> assertThat(f.url()).startsWith("https://"));
	}
}
