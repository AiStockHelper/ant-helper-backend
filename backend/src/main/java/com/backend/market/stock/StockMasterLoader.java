package com.backend.market.stock;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.backend.market.Market;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 앱 시작 시 한국투자증권이 공개하는 종목 마스터 파일을 받아 stock_master 를 채운다.
 * 받기에 실패해도 내장 시드 목록으로 최소한의 검색은 가능하다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockMasterLoader {

	private static final String BASE = "https://new.real.download.dws.co.kr/common/master/";
	private static final Charset CP949 = Charset.forName("MS949");

	private final StockMasterRepository repository;

	@EventListener(ApplicationReadyEvent.class)
	public void onReady() {
		Thread.ofVirtual().name("stock-master-loader").start(this::load);
	}

	public void load() {
		try {
			loadSeedIfEmpty();
			if (repository.countByMarket(Market.KRX) < 500) {
				loadKrx("kospi_code.mst.zip", "kospi_code.mst", 228);
				loadKrx("kosdaq_code.mst.zip", "kosdaq_code.mst", 222);
			}
			if (repository.countByMarket(Market.NASDAQ) < 500) {
				loadUs("nasmst.cod.zip", Market.NASDAQ);
				loadUs("nysmst.cod.zip", Market.NYSE);
				loadUs("amsmst.cod.zip", Market.AMEX);
			}
			log.info("종목 마스터 준비 완료: KRX={}, NASDAQ={}, NYSE={}, AMEX={}",
				repository.countByMarket(Market.KRX), repository.countByMarket(Market.NASDAQ),
				repository.countByMarket(Market.NYSE), repository.countByMarket(Market.AMEX));
		} catch (Exception e) {
			log.warn("종목 마스터 적재 중 오류(내장 시드로 계속 동작합니다): {}", e.toString());
		}
	}

	private void loadSeedIfEmpty() throws IOException {
		if (repository.count() > 0) {
			return;
		}
		List<StockMaster> rows = new ArrayList<>();
		try (BufferedReader br = new BufferedReader(
			new InputStreamReader(new ClassPathResource("stock-seed.csv").getInputStream(), StandardCharsets.UTF_8))) {
			br.readLine();
			String line;
			while ((line = br.readLine()) != null) {
				String[] p = line.split(",", -1);
				if (p.length < 4) {
					continue;
				}
				rows.add(new StockMaster(Market.parse(p[0]), p[1].trim(), p[2].trim(), p[3].trim(), "ST"));
			}
		}
		repository.saveAll(rows);
		log.info("내장 시드 종목 {}개를 적재했습니다.", rows.size());
	}

	/** 국내: 고정폭 파일. 앞부분(단축코드 9 + 표준코드 12 + 종목명)과 뒤쪽 고정 꼬리(tailLength)로 구성된다. */
	private void loadKrx(String zipName, String entryName, int tailLength) {
		try {
			String text = readEntry(download(zipName), entryName);
			Set<String> existing = existingKeys(Market.KRX);
			List<StockMaster> rows = new ArrayList<>();
			String[] lines = text.split("\r?\n");
			// 꼬리(고정폭 부가정보) 길이는 파일 버전마다 1~2자 바뀐 적이 있어 데이터에서 직접 감지한다.
			tailLength = detectTailLength(lines, tailLength);
			for (String row : lines) {
				if (row.length() <= tailLength + 21) {
					continue;
				}
				String head = row.substring(0, row.length() - tailLength);
				String tail = row.substring(row.length() - tailLength);
				String group = tail.substring(0, 2);
				if (!group.equals("ST") && !group.equals("EF")) {
					continue;
				}
				String symbol = head.substring(0, 9).trim();
				String name = head.substring(21).trim();
				if (symbol.isEmpty() || name.isEmpty() || !existing.add(symbol)) {
					continue;
				}
				rows.add(new StockMaster(Market.KRX, symbol, name, null, group));
			}
			saveInChunks(rows);
			log.info("{} 적재: {}개", zipName, rows.size());
		} catch (Exception e) {
			log.warn("{} 적재 실패: {}", zipName, e.toString());
		}
	}

	/** 해외: 탭으로 구분된 텍스트. 4번째=심볼, 6번째=한글명, 7번째=영문명, 8번째=구분(2:주식, 3:ETF). */
	private void loadUs(String zipName, Market market) {
		try {
			String entry = zipName.replace(".zip", "");
			String text = readEntry(download(zipName), entry);
			Set<String> existing = existingKeys(market);
			List<StockMaster> rows = new ArrayList<>();
			for (String row : text.split("\r?\n")) {
				String[] c = row.split("\t", -1);
				if (c.length < 9) {
					continue;
				}
				String type = c[8].trim();
				if (!type.equals("2") && !type.equals("3")) {
					continue;
				}
				String symbol = c[4].trim();
				String korean = c[6].trim();
				String english = c[7].trim();
				String name = korean.isEmpty() ? english : korean;
				if (symbol.isEmpty() || name.isEmpty() || !existing.add(symbol)) {
					continue;
				}
				rows.add(new StockMaster(market, symbol, name, english, type.equals("3") ? "EF" : "ST"));
			}
			saveInChunks(rows);
			log.info("{} 적재: {}개", zipName, rows.size());
		} catch (Exception e) {
			log.warn("{} 적재 실패: {}", zipName, e.toString());
		}
	}

	private static final Set<String> GROUP_CODES =
		Set.of("ST", "EF", "EN", "MF", "RT", "IF", "FS", "SC", "BC", "DR", "SR", "EW", "FE");

	/** 각 행의 꼬리 첫 2자가 유효한 그룹 코드로 가장 많이 읽히는 길이를 고른다. */
	public static int detectTailLength(String[] lines, int expected) {
		int best = expected;
		int bestHits = -1;
		for (int t = expected - 6; t <= expected + 6; t++) {
			int hits = 0;
			int sampled = 0;
			for (String row : lines) {
				if (row.length() <= t + 21) {
					continue;
				}
				if (++sampled > 400) {
					break;
				}
				if (GROUP_CODES.contains(row.substring(row.length() - t, row.length() - t + 2))) {
					hits++;
				}
			}
			if (hits > bestHits) {
				bestHits = hits;
				best = t;
			}
		}
		return best;
	}

	private Set<String> existingKeys(Market market) {
		Set<String> keys = new HashSet<>();
		repository.findAll().stream().filter(s -> s.getMarket() == market).forEach(s -> keys.add(s.getSymbol()));
		return keys;
	}

	private void saveInChunks(List<StockMaster> rows) {
		for (int i = 0; i < rows.size(); i += 1000) {
			repository.saveAll(rows.subList(i, Math.min(rows.size(), i + 1000)));
		}
	}

	private byte[] download(String name) throws IOException, InterruptedException {
		HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
			.followRedirects(HttpClient.Redirect.NORMAL).build();
		HttpResponse<byte[]> res = client.send(
			HttpRequest.newBuilder(URI.create(BASE + name)).timeout(Duration.ofSeconds(60)).GET().build(),
			HttpResponse.BodyHandlers.ofByteArray());
		if (res.statusCode() != 200) {
			throw new IOException("HTTP " + res.statusCode());
		}
		return res.body();
	}

	private String readEntry(byte[] zip, String entryName) throws IOException {
		try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(zip))) {
			ZipEntry e;
			while ((e = zin.getNextEntry()) != null) {
				if (e.getName().equalsIgnoreCase(entryName) || e.getName().toLowerCase().endsWith(".mst")
					|| e.getName().toLowerCase().endsWith(".cod")) {
					return new String(zin.readAllBytes(), CP949);
				}
			}
		}
		throw new IOException("zip 안에 " + entryName + " 이(가) 없습니다.");
	}
}
