package com.backend.domains.agent.skill;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * AI 가 매긴 산업 ETF 순위(JSON)를 읽는다. 후보 목록에 없는 코드와 중복은 버린다.
 * JSON 으로 읽을 수 없거나 순위가 비어 있으면 null 을 돌려 주고, 호출한 쪽은 그달 리밸런싱을 하지 않는다.
 */
final class SectorRankParser {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private SectorRankParser() {
	}

	record Ranking(List<String> order, String rationale, int rejected) {
	}

	static Ranking parse(String text, Set<String> codes) {
		if (text == null) {
			return null;
		}
		int from = text.indexOf('{');
		int to = text.lastIndexOf('}');
		if (from < 0 || to <= from) {
			return null;
		}
		JsonNode root;
		try {
			root = MAPPER.readTree(text.substring(from, to + 1));
		} catch (Exception e) {
			return null;
		}
		JsonNode arr = root.get("ranking");
		if (arr == null || !arr.isArray()) {
			return null;
		}
		Set<String> seen = new LinkedHashSet<>();
		int rejected = 0;
		for (JsonNode n : arr) {
			String code = n.asText("").trim().toUpperCase(Locale.ROOT);
			if (codes.contains(code) && !seen.contains(code)) {
				seen.add(code);
			} else {
				rejected++;
			}
		}
		if (seen.isEmpty()) {
			return null;
		}
		String why = root.path("rationale").asText("").replace('\n', ' ').trim();
		return new Ranking(new ArrayList<>(seen), why.length() > 300 ? why.substring(0, 300) + "…" : why, rejected);
	}
}
