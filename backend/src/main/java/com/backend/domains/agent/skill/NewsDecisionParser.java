package com.backend.domains.agent.skill;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * AI 응답(JSON)의 형식을 검증해 읽는다. 종목이 실제로 있는지·거래 가능한지는 여기서 보지 않고 {@link NewsPickValidator} 가 본다.
 * 형식이 틀린 항목, 이미 보유한 종목의 매수, 보유하지 않은 종목의 매도, 중복은 버린다.
 * 응답 전체가 JSON 으로 읽히지 않으면 null 을 돌려 주고, 호출한 쪽은 그날 주문을 하지 않는다.
 */
final class NewsDecisionParser {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	/** AI 가 한 번에 제안할 수 있는 매수 항목 수의 상한(과도한 응답 방지) */
	static final int MAX_BUYS = 10;

	private NewsDecisionParser() {
	}

	record Pick(String symbol, String name, int conviction, String reason) {
	}

	record Sell(String symbol, String reason) {
	}

	/** rejected: 규칙에 맞지 않아 버린 항목 수 */
	record Decision(String marketView, List<Pick> buys, List<Sell> sells, int rejected) {
	}

	static Decision parse(String text, Set<String> held) {
		JsonNode root = readJson(text);
		if (root == null || !root.isObject()) {
			return null;
		}
		int rejected = 0;
		List<Pick> buys = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		JsonNode b = root.get("buys");
		if (b != null && b.isArray()) {
			for (JsonNode n : b) {
				String symbol = upper(n.path("symbol").asText(""));
				String name = clip(n.path("name").asText(""), 80);
				int conviction = conviction(n.get("conviction"));
				if (buys.size() >= MAX_BUYS || symbol.isEmpty() || symbol.length() > 12 || !symbol.matches("[0-9A-Z.\\-]+")
					|| held.contains(symbol) || conviction < 1 || !seen.add(symbol)) {
					rejected++;
					continue;
				}
				buys.add(new Pick(symbol, name, conviction, clip(n.path("reason").asText(""), 160)));
			}
		}
		List<Sell> sells = new ArrayList<>();
		Set<String> sold = new HashSet<>();
		JsonNode s = root.get("sells");
		if (s != null && s.isArray()) {
			for (JsonNode n : s) {
				String symbol = upper(n.path("symbol").asText(""));
				if (symbol.isEmpty() || !held.contains(symbol) || !sold.add(symbol)) {
					rejected++;
					continue;
				}
				sells.add(new Sell(symbol, clip(n.path("reason").asText(""), 160)));
			}
		}
		buys.sort((x, y) -> Integer.compare(y.conviction(), x.conviction()));
		return new Decision(clip(root.path("marketView").asText(""), 300), buys, sells, rejected);
	}

	/** 코드블록이나 설명이 섞여 있어도 첫 '{' 부터 마지막 '}' 까지를 JSON 으로 읽는다. */
	private static JsonNode readJson(String text) {
		if (text == null) {
			return null;
		}
		int from = text.indexOf('{');
		int to = text.lastIndexOf('}');
		if (from < 0 || to <= from) {
			return null;
		}
		try {
			return MAPPER.readTree(text.substring(from, to + 1));
		} catch (Exception e) {
			return null;
		}
	}

	private static int conviction(JsonNode n) {
		if (n == null) {
			return 0;
		}
		if (n.isNumber()) {
			return clamp((int)Math.round(n.asDouble()));
		}
		if (n.isTextual()) {
			try {
				return clamp((int)Math.round(Double.parseDouble(n.asText().trim())));
			} catch (NumberFormatException e) {
				return 0;
			}
		}
		return 0;
	}

	private static int clamp(int v) {
		return v < 1 ? 0 : Math.min(v, 5);
	}

	private static String upper(String s) {
		return s == null ? "" : s.trim().toUpperCase(Locale.ROOT);
	}

	private static String clip(String s, int max) {
		String t = s == null ? "" : s.replace('\n', ' ').trim();
		return t.length() > max ? t.substring(0, max) + "…" : t;
	}
}
