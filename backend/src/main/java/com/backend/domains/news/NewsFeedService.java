package com.backend.domains.news;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import lombok.extern.slf4j.Slf4j;

/**
 * 한국·미국 경제 뉴스 RSS 를 모아 주는 서비스. 키가 필요 없는 공개 피드만 쓴다.
 * 피드마다 몇 분간 결과를 기억해 두므로 여러 자동 실행이 같은 피드를 반복해서 두드리지 않는다.
 * 일부 피드가 실패해도 나머지로 계속하고, 전부 실패하면 빈 목록을 돌려준다.
 */
@Slf4j
@Component
public class NewsFeedService {

	public record Feed(String name, String url) {
	}

	@FunctionalInterface
	interface Fetcher {
		byte[] get(String url) throws IOException, InterruptedException;
	}

	static final List<Feed> DEFAULT_KRX = List.of(
		new Feed("연합뉴스 경제", "https://www.yna.co.kr/rss/economy.xml"),
		new Feed("한국경제 증권", "https://www.hankyung.com/feed/finance"),
		new Feed("한국경제 경제", "https://www.hankyung.com/feed/economy"),
		new Feed("매일경제 증권", "https://www.mk.co.kr/rss/50200011/"),
		new Feed("매일경제 경제", "https://www.mk.co.kr/rss/30100041/"),
		new Feed("구글뉴스 경제", "https://news.google.com/rss/headlines/section/topic/BUSINESS?hl=ko&gl=KR&ceid=KR:ko"));

	static final List<Feed> DEFAULT_US = List.of(
		new Feed("CNBC Top News", "https://www.cnbc.com/id/100003114/device/rss/rss.html"),
		new Feed("CNBC Finance", "https://www.cnbc.com/id/10000664/device/rss/rss.html"),
		new Feed("MarketWatch", "https://feeds.content.dowjones.io/public/rss/mw_topstories"),
		new Feed("NYT Business", "https://rss.nytimes.com/services/xml/rss/nyt/Business.xml"),
		new Feed("BBC Business", "https://feeds.bbci.co.uk/news/business/rss.xml"),
		new Feed("Google News Business", "https://news.google.com/rss/headlines/section/topic/BUSINESS?hl=en-US&gl=US&ceid=US:en"),
		new Feed("Google News Markets", "https://news.google.com/rss/search?q=stock+market+when:1d&hl=en-US&gl=US&ceid=US:en"));

	private static final Duration FRESH_FOR = Duration.ofMinutes(10);
	private static final Duration FAILED_FOR = Duration.ofMinutes(2);
	private static final int SUMMARY_MAX = 200;
	private static final List<java.time.format.DateTimeFormatter> DATE_FORMATS = List.of(
		DateTimeFormatter.RFC_1123_DATE_TIME,
		DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss XXX", Locale.ENGLISH),
		DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss Z", Locale.ENGLISH),
		DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss zzz", Locale.ENGLISH));

	private record Cached(Instant at, boolean ok, List<NewsItem> items) {
	}

	private final Fetcher fetcher;
	private final Clock clock;
	private final List<Feed> krx;
	private final List<Feed> us;
	private final Map<String, Cached> cache = new ConcurrentHashMap<>();

	/** 운영용. app.news.feeds-krx / feeds-us 에 "이름|주소;이름|주소" 로 적으면 기본 피드를 대신한다. */
	@Autowired
	public NewsFeedService(@Value("${app.news.feeds-krx:}") String krxOverride, @Value("${app.news.feeds-us:}") String usOverride) {
		this(httpFetcher(), Clock.systemUTC(), krxOverride, usOverride);
	}

	NewsFeedService(Fetcher fetcher, Clock clock, String krxOverride, String usOverride) {
		this.fetcher = fetcher;
		this.clock = clock;
		this.krx = parseFeeds(krxOverride, DEFAULT_KRX);
		this.us = parseFeeds(usOverride, DEFAULT_US);
	}

	/**
	 * 최근 maxAge 안에 나온 뉴스를 새것부터, 출처가 고르게 섞이도록 limit 건까지.
	 *
	 * @param group "KRX" 또는 "US"
	 */
	public List<NewsItem> recent(String group, Duration maxAge, int limit) {
		List<Feed> feeds = "US".equalsIgnoreCase(group) ? us : krx;
		Instant now = clock.instant();
		List<List<NewsItem>> perFeed = feeds.parallelStream().map(f -> load(f, now)).toList();
		return merge(perFeed, now, maxAge, limit);
	}

	/** 사용할 수 있는 피드 이름(점검용) */
	public List<String> feedNames(String group) {
		return ("US".equalsIgnoreCase(group) ? us : krx).stream().map(Feed::name).toList();
	}

	private List<NewsItem> load(Feed feed, Instant now) {
		Cached c = cache.get(feed.url());
		if (c != null && Duration.between(c.at(), now).compareTo(c.ok() ? FRESH_FOR : FAILED_FOR) < 0) {
			return c.items();
		}
		try {
			List<NewsItem> items = parse(fetcher.get(feed.url()), feed.name());
			cache.put(feed.url(), new Cached(now, true, items));
			return items;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return c == null ? List.of() : c.items();
		} catch (Exception e) {
			log.info("뉴스 피드를 읽지 못했습니다 {}: {}", feed.name(), e.toString());
			List<NewsItem> stale = c == null ? List.of() : c.items();     // 실패하면 직전에 받은 것을 잠시 더 쓴다
			cache.put(feed.url(), new Cached(now, false, stale));
			return stale;
		}
	}

	// ─────────────────────────── 합치기 ───────────────────────────

	/** 기간 필터 → 제목 중복 제거 → 피드별 최신순 → 피드를 번갈아 가며 limit 건. */
	static List<NewsItem> merge(List<List<NewsItem>> perFeed, Instant now, Duration maxAge, int limit) {
		Instant oldest = now.minus(maxAge);
		Instant latest = now.plus(Duration.ofHours(1));          // 시계가 어긋난 미래 날짜는 버린다
		Set<String> seen = new HashSet<>();
		List<List<NewsItem>> lanes = new ArrayList<>();
		for (List<NewsItem> items : perFeed) {
			List<NewsItem> lane = new ArrayList<>();
			items.stream().filter(i -> i.publishedAt() != null && !i.publishedAt().isBefore(oldest) && !i.publishedAt().isAfter(latest))
				.sorted(Comparator.comparing(NewsItem::publishedAt).reversed()).forEach(i -> {
					if (seen.add(titleKey(i.title()))) {
						lane.add(i);
					}
				});
			lanes.add(lane);
		}
		List<NewsItem> out = new ArrayList<>();
		for (int round = 0; out.size() < limit; round++) {
			boolean any = false;
			for (List<NewsItem> lane : lanes) {
				if (round < lane.size() && out.size() < limit) {
					out.add(lane.get(round));
					any = true;
				}
			}
			if (!any) {
				break;
			}
		}
		out.sort(Comparator.comparing(NewsItem::publishedAt).reversed());
		return out;
	}

	static String titleKey(String title) {
		String k = title == null ? "" : title.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
		return k.length() > 50 ? k.substring(0, 50) : k;
	}

	// ─────────────────────────── RSS/Atom 해석 ───────────────────────────

	static List<NewsItem> parse(byte[] xml, String source) throws Exception {
		DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
		f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);       // 외부 엔티티·DTD 공격 차단
		f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
		f.setXIncludeAware(false);
		f.setExpandEntityReferences(false);
		org.w3c.dom.Document doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
		NodeList nodes = doc.getElementsByTagName("item");
		if (nodes.getLength() == 0) {
			nodes = doc.getElementsByTagName("entry");
		}
		List<NewsItem> out = new ArrayList<>();
		for (int i = 0; i < nodes.getLength(); i++) {
			Element e = (Element)nodes.item(i);
			String title = clean(childText(e, "title"), 300);
			Instant at = parseDate(childText(e, "pubDate", "published", "updated", "dc:date"));
			if (title.isEmpty() || at == null) {
				continue;
			}
			String summary = clean(childText(e, "description", "summary", "content"), SUMMARY_MAX);
			if (summary.toLowerCase(Locale.ROOT).startsWith(title.toLowerCase(Locale.ROOT))) {
				summary = "";           // 구글뉴스처럼 요약이 제목의 반복인 경우
			}
			out.add(new NewsItem(source, title, summary, at));
		}
		return out;
	}

	private static String childText(Element parent, String... names) {
		for (String name : names) {
			NodeList kids = parent.getChildNodes();
			for (int i = 0; i < kids.getLength(); i++) {
				Node n = kids.item(i);
				if (n.getNodeType() == Node.ELEMENT_NODE && n.getNodeName().equalsIgnoreCase(name)) {
					String t = n.getTextContent();
					if (t != null && !t.isBlank()) {
						return t;
					}
				}
			}
		}
		return "";
	}

	static Instant parseDate(String text) {
		if (text == null || text.isBlank()) {
			return null;
		}
		String t = text.trim();
		for (DateTimeFormatter f : DATE_FORMATS) {
			try {
				return ZonedDateTime.parse(t, f).toInstant();
			} catch (RuntimeException ignored) {
				// 다음 형식 시도
			}
		}
		try {
			return OffsetDateTime.parse(t).toInstant();           // Atom(ISO-8601)
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** HTML 태그와 흔한 엔티티를 지우고 공백을 정리한 뒤 max 글자로 자른다. */
	static String clean(String html, int max) {
		if (html == null) {
			return "";
		}
		String s = html.replaceAll("(?is)<script.*?</script>|<style.*?</style>", " ").replaceAll("<[^>]+>", " ")
			.replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<")
			.replace("&gt;", ">").replaceAll("\\s+", " ").trim();
		return s.length() > max ? s.substring(0, max).trim() + "…" : s;
	}

	// ─────────────────────────── 설정·통신 ───────────────────────────

	static List<Feed> parseFeeds(String text, List<Feed> defaults) {
		if (text == null || text.isBlank()) {
			return defaults;
		}
		List<Feed> out = new ArrayList<>();
		for (String part : text.split(";")) {
			String[] kv = part.trim().split("\\|", 2);
			if (kv.length == 2 && kv[1].trim().startsWith("http")) {
				out.add(new Feed(kv[0].trim(), kv[1].trim()));
			}
		}
		return out.isEmpty() ? defaults : out;
	}

	private static Fetcher httpFetcher() {
		HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NORMAL).build();
		return url -> {
			HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
				.header("User-Agent", "Mozilla/5.0 (compatible; AntHelperNewsReader/1.0)")
				.header("Accept", "application/rss+xml, application/xml, text/xml, */*").GET().build();
			HttpResponse<byte[]> res = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
			if (res.statusCode() / 100 != 2) {
				throw new IOException("HTTP " + res.statusCode());
			}
			return res.body();
		};
	}
}
