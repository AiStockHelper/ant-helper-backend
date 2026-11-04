package com.backend.domains.chat.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.backend.domains.chat.dto.ChatDtos.Action;
import com.backend.domains.chat.dto.ChatDtos.ActionResult;
import com.backend.domains.chat.entity.ChatMessage;
import com.backend.domains.chat.memory.ConversationMemory.StockRef;
import com.backend.order.kis.enums.AccountType;
import com.fasterxml.jackson.databind.ObjectMapper;

class ConversationMemoryTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static ChatMessage user(long id, String text) {
		ChatMessage m = ChatMessage.user(1L, 1L, text);
		ReflectionTestUtils.setField(m, "id", id);
		return m;
	}

	private static ChatMessage assistant(long id, String text) {
		ChatMessage m = ChatMessage.assistantPlaceholder(1L, 1L);
		m.finish(text, ChatMessage.Status.COMPLETE, null, null, "gpt-4.1");
		ReflectionTestUtils.setField(m, "id", id);
		return m;
	}

	private static ChatMessage withStatus(ChatMessage m, ChatMessage.Status status) {
		ReflectionTestUtils.setField(m, "status", status);
		return m;
	}

	private static List<Long> ids(List<ChatMessage> list) {
		return list.stream().map(ChatMessage::getId).toList();
	}

	// ───────────── 최근 대화 창 ─────────────

	@Test
	void windowKeepsNewestMessagesWithinBudgetAndStartsWithUser() {
		List<ChatMessage> all = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			all.add(user(i * 2 + 1, "질문".repeat(50)));       // 100자
			all.add(assistant(i * 2 + 2, "답변".repeat(50)));  // 100자
		}
		// 예산 450자 → 최신 4개(400자)까지. 맨 앞이 assistant 면 잘라서 user 로 시작해야 한다.
		List<ChatMessage> window = ConversationMemory.selectWindow(all, 450, 100, m -> m.getContent().length());
		assertThat(window).isNotEmpty();
		assertThat(window.get(0).getRole()).isEqualTo(ChatMessage.Role.USER);
		assertThat(window.get(window.size() - 1).getId()).isEqualTo(20L);
		assertThat(window.stream().mapToInt(m -> m.getContent().length()).sum()).isLessThanOrEqualTo(450);
	}

	@Test
	void windowAlwaysKeepsAtLeastTheLastExchangeEvenIfOverBudget() {
		List<ChatMessage> all = List.of(user(1, "가".repeat(5000)), assistant(2, "나".repeat(5000)));
		List<ChatMessage> window = ConversationMemory.selectWindow(all, 100, 100, m -> m.getContent().length());
		assertThat(ids(window)).containsExactly(1L, 2L);
	}

	@Test
	void windowRespectsMessageCountCap() {
		List<ChatMessage> all = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			all.add(user(i * 2 + 1, "q"));
			all.add(assistant(i * 2 + 2, "a"));
		}
		assertThat(ConversationMemory.selectWindow(all, 100_000, 6, m -> 1)).hasSizeLessThanOrEqualTo(6);
	}

	@Test
	void windowSkipsEmptyErrorAndStreamingMessages() {
		ChatMessage failed = withStatus(assistant(2, ""), ChatMessage.Status.ERROR);
		ChatMessage streaming = withStatus(assistant(4, "생성 중"), ChatMessage.Status.STREAMING);
		List<ChatMessage> all = List.of(user(1, "첫 질문"), failed, user(3, "다시 질문"), streaming, user(5, "마지막"));
		assertThat(ids(ConversationMemory.selectWindow(all, 10_000, 100, m -> m.getContent().length())))
			.containsExactly(1L, 3L, 5L);
	}

	// ───────────── 최근 다룬 종목 ─────────────

	@Test
	void extractsStocksNewestFirstAndDeduplicates() {
		String newer = """
			[{"type":"quote","data":{"productNumber":"AAPL","name":"애플","market":"NASDAQ"}},
			 {"type":"prediction","data":{"productNumber":"005930","name":"삼성전자","market":"KRX"}}]""";
		String older = """
			[{"type":"quote","data":{"productNumber":"005930","name":"삼성전자","market":"KRX"}},
			 {"type":"candles","data":{"productNumber":"NVDA","name":"엔비디아","market":"NASDAQ"}}]""";
		List<StockRef> stocks = ConversationMemory.extractStocks(List.of(newer, older), MAPPER, 6);
		// 같은 메시지에서는 뒤 카드가 더 최근 → 삼성전자, 애플, 엔비디아 (삼성전자 중복 제거)
		assertThat(stocks).extracting(StockRef::name).containsExactly("삼성전자", "애플", "엔비디아");
		assertThat(stocks.get(0).market()).isEqualTo("KRX");
	}

	@Test
	void extractsOnlyTopRecommendationsAndIgnoresNonStockCards() {
		String cards = """
			[{"type":"balance","data":{"accountId":3}},
			 {"type":"search","data":[{"productNumber":"TSLA","name":"테슬라","market":"NASDAQ"}]},
			 {"type":"recommendations","data":[
			   {"productNumber":"1","name":"A","market":"KRX"},{"productNumber":"2","name":"B","market":"KRX"},
			   {"productNumber":"3","name":"C","market":"KRX"},{"productNumber":"4","name":"D","market":"KRX"}]}]""";
		List<StockRef> stocks = ConversationMemory.extractStocks(List.of(cards), MAPPER, 6);
		assertThat(stocks).extracting(StockRef::name).containsExactly("A", "B", "C"); // 검색 결과/잔고는 제외, 추천은 상위 3개
	}

	@Test
	void extractRespectsLimitAndToleratesBrokenJson() {
		String many = "[" + "{\"type\":\"quote\",\"data\":{\"productNumber\":\"1\",\"name\":\"a\",\"market\":\"KRX\"}},"
			+ "{\"type\":\"quote\",\"data\":{\"productNumber\":\"2\",\"name\":\"b\",\"market\":\"KRX\"}},"
			+ "{\"type\":\"quote\",\"data\":{\"productNumber\":\"3\",\"name\":\"c\",\"market\":\"KRX\"}}]";
		assertThat(ConversationMemory.extractStocks(List.of("{broken", "", many), MAPPER, 2)).hasSize(2);
	}

	// ───────────── 맥락 블록 ─────────────

	@Test
	void blockIsEmptyWhenThereIsNothingToRemember() {
		assertThat(ConversationMemory.renderBlock(null, List.of(), List.of())).isEmpty();
		assertThat(ConversationMemory.renderBlock("  ", List.of(), List.of())).isEmpty();
	}

	@Test
	void blockContainsSummaryStocksAndActions() {
		Action pending = new Action(1L, "ORDER", "PENDING", "삼성전자 3주 매수", "", 4L, AccountType.REAL_TRADE, "실전",
			List.of(), null, null, null);
		Action done = new Action(2L, "ORDER", "EXECUTED", "애플 1주 매수", "", 3L, AccountType.PAPER_TRADE, "모의",
			List.of(), new ActionResult(true, "주문이 접수되었습니다.", "0000000001"), null, null);
		List<String> lines = ConversationMemory.renderActions(List.of(pending, done));
		assertThat(lines.get(0)).contains("삼성전자 3주 매수").contains("승인 대기 중").contains("실전");
		assertThat(lines.get(1)).contains("승인되어 실행됨").contains("모의").contains("주문이 접수되었습니다.");

		String block = ConversationMemory.renderBlock("- 사용자는 반도체 종목에 관심", List.of(
			new StockRef("005930", "삼성전자", "KRX")), lines);
		assertThat(block).contains("## 이 대화의 기억").contains("앞부분 요약").contains("반도체")
			.contains("삼성전자 (005930, KRX)").contains("확인 카드").contains("승인 대기 중");
	}

	@Test
	void summaryIsTrimmedAtALineBoundary() {
		String text = "- 첫째 항목입니다\n- 둘째 항목입니다\n- 셋째 항목은 아주 길어서 잘려야 합니다 " + "가".repeat(200);
		String trimmed = ConversationMemory.trimToLimit(text, 40);
		assertThat(trimmed.length()).isLessThanOrEqualTo(40);
		assertThat(trimmed).startsWith("- 첫째 항목입니다");
	}

	@Test
	void historySnippetCentersOnTheMatch() {
		String content = "앞부분 ".repeat(40) + "엔비디아 실적이 좋아서 관심 종목에 추가했다" + " 뒷부분".repeat(40);
		String s = ChatHistorySearchService.snippet(content, "엔비디아");
		assertThat(s).contains("엔비디아").startsWith("…").endsWith("…");
		assertThat(s.length()).isLessThan(300);
	}
}
