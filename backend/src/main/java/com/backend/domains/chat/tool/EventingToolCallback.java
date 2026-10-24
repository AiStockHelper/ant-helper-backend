package com.backend.domains.chat.tool;

import java.util.Map;
import java.util.UUID;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import com.backend.common.exception.ApiException;
import com.backend.domains.chat.dto.ChatDtos.ToolCall;
import com.backend.order.kis.kis_client.exception.KisClientException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * 도구 호출을 감싸서 (1) 화면에 보여줄 시작/종료 이벤트를 내보내고 (2) 예외를 LLM 이 읽을 수 있는 오류 JSON 으로 바꾼다.
 */
@Slf4j
public class EventingToolCallback implements ToolCallback {

	private static final Map<String, String> LABELS = Map.ofEntries(
		Map.entry("search_stock", "종목 검색"),
		Map.entry("get_quote", "현재가 조회"),
		Map.entry("get_candles", "차트 데이터 조회"),
		Map.entry("predict_stock", "AI 예측"),
		Map.entry("recommend_stocks", "추천 종목 분석"),
		Map.entry("get_account_summary", "계좌 잔고 조회"),
		Map.entry("get_order_history", "주문·체결 내역 조회"),
		Map.entry("place_order", "주문 준비"),
		Map.entry("cancel_order", "주문 취소"),
		Map.entry("schedule_order", "예약 주문 준비"),
		Map.entry("schedule_recommend_buy", "예약 추천매수 준비"),
		Map.entry("list_scheduled_tasks", "예약 목록 조회"),
		Map.entry("cancel_scheduled_task", "예약 취소"),
		Map.entry("get_market_status", "장 운영 상태 확인"),
		Map.entry("search_chat_history", "이전 대화 검색")
	);

	private static final String[] ARG_KEYS = {"symbol", "query", "stock", "productNumber", "market"};

	private final ToolCallback delegate;
	private final ObjectMapper mapper;

	public EventingToolCallback(ToolCallback delegate, ObjectMapper mapper) {
		this.delegate = delegate;
		this.mapper = mapper;
	}

	@Override
	public ToolDefinition getToolDefinition() {
		return delegate.getToolDefinition();
	}

	@Override
	public String call(String toolInput) {
		return call(toolInput, null);
	}

	@Override
	public String call(String toolInput, ToolContext toolContext) {
		ChatSession session = toolContext == null ? null : (ChatSession)toolContext.getContext().get("session");
		String name = delegate.getToolDefinition().name();
		String id = UUID.randomUUID().toString();
		String label = label(name, toolInput);
		if (session != null) {
			session.tool(new ToolCall(id, name, label, "RUNNING", null));
		}
		try {
			String result = delegate.call(toolInput, toolContext);
			if (session != null) {
				String summary = session.takeSummary();
				session.tool(new ToolCall(id, name, label, "DONE", summary));
			}
			return result;
		} catch (RuntimeException e) {
			Throwable root = e.getCause() != null && e.getCause() != e ? e.getCause() : e;
			String message = root instanceof ApiException || root instanceof KisClientException
				? root.getMessage() : (e instanceof ApiException || e instanceof KisClientException ? e.getMessage() : null);
			if (message == null) {
				log.warn("도구 {} 실행 중 예기치 않은 오류", name, e);
				message = "도구 실행 중 오류가 발생했습니다.";
			} else {
				log.info("도구 {} 실패: {}", name, message);
			}
			if (session != null) {
				session.takeSummary();
				session.tool(new ToolCall(id, name, label, "ERROR", message));
			}
			try {
				return mapper.writeValueAsString(Map.of("error", message));
			} catch (Exception ex) {
				return "{\"error\":\"도구 실행 실패\"}";
			}
		}
	}

	private String label(String name, String input) {
		String base = LABELS.getOrDefault(name, name);
		try {
			JsonNode node = mapper.readTree(input == null ? "{}" : input);
			for (String key : ARG_KEYS) {
				JsonNode v = node.get(key);
				if (v != null && v.isTextual() && !v.asText().isBlank()) {
					return base + " · " + v.asText();
				}
			}
		} catch (Exception ignored) {
			// 라벨은 부가 정보이므로 파싱 실패는 무시
		}
		return base;
	}
}
