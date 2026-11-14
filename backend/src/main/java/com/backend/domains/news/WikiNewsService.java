package com.backend.domains.news;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * 위키피디아 'Portal:Current events'(일자별 세계 뉴스)로 '그 달의 뉴스 요약'을 만든다.
 * 뉴스 기반 산업 로테이션을 과거 데이터로 시험할 때 쓴 것과 같은 자료·같은 요약 방식이다
 * (분류별 글자 예산 안에서 한 달 전체에 고르게 뽑는다). 한 달치를 한 번의 API 요청으로 받고, 지난 달은 바뀌지 않으므로 기억해 둔다.
 */
@Slf4j
@Component
public class WikiNewsService {

	@FunctionalInterface
	interface Fetcher {
		/** 위키피디아 API 요청 주소를 받아 응답 본문(JSON)을 돌려준다. 요청 한도(429)에 걸리면 IOException. */
		String get(String url) throws IOException, InterruptedException;
	}

	/** 요약에 넣는 분류와 분류별 글자 예산(순서가 곧 우선순위) */
	static final Map<String, Integer> PRIORITY = new LinkedHashMap<>();

	static {
		PRIORITY.put("Business and economy", 9000);
		PRIORITY.put("Science and technology", 3000);
		PRIORITY.put("Health and environment", 2500);
		PRIORITY.put("International relations", 2500);
		PRIORITY.put("Armed conflicts and attacks", 2000);
		PRIORITY.put("Politics and elections", 2500);
		PRIORITY.put("Disasters and accidents", 1000);
		PRIORITY.put("Law and crime", 500);
	}

	static final int ITEM_CHARS = 260;
	private static final int BATCH = 40;
	private static final String API = "https://en.wikipedia.org/w/api.php";

	private final Fetcher fetcher;
	private final Map<YearMonth, String> cache = new ConcurrentHashMap<>();
	private final ObjectMapper mapper = new ObjectMapper();

	/** 요청 실패 뒤 재시도 사이의 기본 대기(테스트에서 줄일 수 있도록 분리) */
	long retryPauseMillis = 2_000L;

	public WikiNewsService() {
		this(httpFetcher());
	}

	WikiNewsService(Fetcher fetcher) {
		this.fetcher = fetcher;
	}

	/** 그 달의 뉴스 요약. 가져오지 못하면 null(호출한 쪽이 나중에 다시 시도한다). 요약 본문만 돌려주고 머리글은 붙이지 않는다. */
	public String monthlyDigest(YearMonth month) {
		String cached = cache.get(month);
		if (cached != null) {
			return cached;
		}
		try {
			List<LocalDate> days = new ArrayList<>();
			for (LocalDate d = month.atDay(1); !d.isAfter(month.atEndOfMonth()); d = d.plusDays(1)) {
				days.add(d);
			}
			Map<LocalDate, Map<String, List<String>>> perDay = new LinkedHashMap<>();
			for (int i = 0; i < days.size(); i += BATCH) {
				List<LocalDate> chunk = days.subList(i, Math.min(i + BATCH, days.size()));
				perDay.putAll(fetchDays(chunk));
			}
			String digest = buildDigest(perDay);
			if (digest.isBlank()) {
				return null;
			}
			cache.put(month, digest);
			return digest;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		} catch (Exception e) {
			log.warn("위키피디아 월간 뉴스를 가져오지 못했습니다({}): {}", month, e.toString());
			return null;
		}
	}

	private Map<LocalDate, Map<String, List<String>>> fetchDays(List<LocalDate> days) throws IOException, InterruptedException {
		StringBuilder titles = new StringBuilder();
		for (LocalDate d : days) {
			titles.append(titles.length() == 0 ? "" : "|").append(pageTitle(d));
		}
		String url = API + "?action=query&prop=revisions&rvprop=content&rvslots=main&format=json&formatversion=2&titles="
			+ URLEncoder.encode(titles.toString(), StandardCharsets.UTF_8);
		IOException last = null;
		for (int attempt = 0; attempt < 3; attempt++) {
			try {
				return parsePages(fetcher.get(url));
			} catch (IOException e) {
				last = e;
				Thread.sleep(retryPauseMillis * (attempt + 1));
			}
		}
		throw last == null ? new IOException("위키피디아 요청 실패") : last;
	}

	Map<LocalDate, Map<String, List<String>>> parsePages(String json) throws IOException {
		JsonNode pages = mapper.readTree(json).path("query").path("pages");
		Map<LocalDate, Map<String, List<String>>> out = new HashMap<>();
		for (JsonNode p : pages) {
			LocalDate d = dateOfTitle(p.path("title").asText(""));
			JsonNode rev = p.path("revisions");
			if (d == null || p.path("missing").asBoolean(false) || !rev.isArray() || rev.isEmpty()) {
				continue;
			}
			out.put(d, parseWikitext(rev.get(0).path("slots").path("main").path("content").asText("")));
		}
		return out;
	}

	static String pageTitle(LocalDate d) {
		return "Portal:Current events/" + d.getYear() + " " + d.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + d.getDayOfMonth();
	}

	private static final Pattern TITLE = Pattern.compile("Portal:Current events/(\\d{4}) (\\w+) (\\d{1,2})");

	static LocalDate dateOfTitle(String title) {
		var m = TITLE.matcher(title == null ? "" : title);
		if (!m.matches()) {
			return null;
		}
		try {
			java.time.Month month = java.time.Month.valueOf(m.group(2).toUpperCase(Locale.ROOT));
			return LocalDate.of(Integer.parseInt(m.group(1)), month, Integer.parseInt(m.group(3)));
		} catch (RuntimeException e) {
			return null;
		}
	}

	// ─────────────────────────── 위키 원문 해석 ───────────────────────────

	private static final Pattern BOLD_HEADING = Pattern.compile("^'''(.+?)'''\\s*$");
	private static final Pattern REF = Pattern.compile("<ref[^>]*/>|<ref[^>]*>.*?</ref>|<!--.*?-->", Pattern.DOTALL);
	private static final Pattern TEMPLATE = Pattern.compile("\\{\\{[^{}]*\\}\\}");
	private static final Pattern EXT_LINK = Pattern.compile("\\[https?://[^\\s\\]]+[^\\]]*\\]");
	private static final Pattern FILE_LINK = Pattern.compile("\\[\\[(?:File|Image):[^\\]]*\\]\\]");
	private static final Pattern PIPED_LINK = Pattern.compile("\\[\\[[^\\]|]*\\|([^\\]]*)\\]\\]");
	private static final Pattern PLAIN_LINK = Pattern.compile("\\[\\[([^\\]]*)\\]\\]");

	static String clean(String raw) {
		String s = REF.matcher(raw).replaceAll("");
		for (int i = 0; i < 3; i++) {
			s = TEMPLATE.matcher(s).replaceAll("");
		}
		s = EXT_LINK.matcher(s).replaceAll("");
		s = FILE_LINK.matcher(s).replaceAll("");
		s = PIPED_LINK.matcher(s).replaceAll("$1");
		s = PLAIN_LINK.matcher(s).replaceAll("$1");
		s = s.replaceAll("'{2,}", "").replaceAll("<[^>]+>", "").replace("&nbsp;", " ");
		s = s.replaceAll("\\s+", " ").trim();
		s = s.replaceAll("\\s+([,.;:!?)])", "$1");
		return s.replaceAll("(\\(\\s*\\)\\s*)+$", "").trim();
	}

	/** 하루치 위키 원문 → 분류 이름 → 항목 목록("주제: 내용" 형태). */
	static Map<String, List<String>> parseWikitext(String content) {
		Map<String, List<String>> out = new LinkedHashMap<>();
		String[] lines = content.split("\\r?\\n");
		String category = null;
		String topic = "";
		for (int i = 0; i < lines.length; i++) {
			String line = lines[i].stripTrailing();
			var bold = BOLD_HEADING.matcher(line);
			if (line.startsWith(";") || bold.matches()) {
				category = clean(line.startsWith(";") ? line.substring(1) : bold.group(1));
				out.computeIfAbsent(category, k -> new ArrayList<>());
				topic = "";
			} else if (line.startsWith("*") && !line.startsWith("**")) {
				String text = clean(line.substring(1));
				String next = i + 1 < lines.length ? lines[i + 1] : "";
				if (next.startsWith("**")) {
					topic = text;
				} else {
					topic = "";
					if (text.length() > 3 && category != null) {
						out.get(category).add(text);
					}
				}
			} else if (line.startsWith("**")) {
				String text = clean(line.replaceFirst("^\\*+", ""));
				if (text.length() > 3 && category != null) {
					out.get(category).add(topic.isEmpty() ? text : topic + ": " + text);
				}
			}
		}
		return out;
	}

	// ─────────────────────────── 요약 만들기 ───────────────────────────

	private record Item(String day, String text) {
		int cost() {
			return Math.min(text.length(), ITEM_CHARS) + 8;
		}
	}

	/** 분류별 글자 예산 안에서 한 달 전체에 고르게 항목을 뽑아 "## 분류" 묶음으로 만든다. */
	static String buildDigest(Map<LocalDate, Map<String, List<String>>> perDay) {
		Map<String, List<Item>> byCategory = new LinkedHashMap<>();
		perDay.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(day -> {
			String mmdd = String.format("%02d-%02d", day.getKey().getMonthValue(), day.getKey().getDayOfMonth());
			day.getValue().forEach((cat, items) -> items.forEach(t -> byCategory.computeIfAbsent(cat, k -> new ArrayList<>()).add(new Item(mmdd, t))));
		});
		List<String> parts = new ArrayList<>();
		for (Map.Entry<String, Integer> e : PRIORITY.entrySet()) {
			List<Item> items = spread(byCategory.getOrDefault(e.getKey(), List.of()), e.getValue());
			if (items.isEmpty()) {
				continue;
			}
			StringBuilder sb = new StringBuilder("## ").append(e.getKey());
			for (Item it : items) {
				sb.append('\n').append(it.day()).append(' ').append(it.text(), 0, Math.min(it.text().length(), ITEM_CHARS));
			}
			parts.add(sb.toString());
		}
		return String.join("\n\n", parts);
	}

	private static List<Item> spread(List<Item> items, int budget) {
		int total = items.stream().mapToInt(Item::cost).sum();
		if (total <= budget || items.isEmpty()) {
			return items;
		}
		int keep = Math.max(1, (int)(items.size() * (double)budget / total));
		List<Item> out = new ArrayList<>();
		int last = -1;
		for (int k = 0; k < keep; k++) {
			int pos = keep == 1 ? 0 : (int)Math.round(k * (items.size() - 1.0) / (keep - 1));
			if (pos != last) {
				out.add(items.get(pos));
				last = pos;
			}
		}
		return out;
	}

	private static Fetcher httpFetcher() {
		HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();
		return url -> {
			HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60))
				.header("User-Agent", "ant-helper-research/1.0 (news sector rotation)").GET().build();
			HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			if (res.statusCode() == 429) {
				long wait = res.headers().firstValue("Retry-After").map(Long::parseLong).orElse(30L);
				Thread.sleep(Math.min(wait, 30) * 1000);
				throw new IOException("요청 한도(429)");
			}
			if (res.statusCode() / 100 != 2) {
				throw new IOException("HTTP " + res.statusCode());
			}
			return res.body();
		};
	}
}
