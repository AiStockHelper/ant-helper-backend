package com.backend.domains.chat.memory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.backend.common.properties.AppProperties;
import com.backend.common.util.Json;
import com.backend.domains.chat.ChatMapper;
import com.backend.domains.chat.ai.LlmGateway;
import com.backend.domains.chat.dto.ChatDtos.Action;
import com.backend.domains.chat.dto.ChatDtos.ToolCall;
import com.backend.domains.chat.entity.ChatConversation;
import com.backend.domains.chat.entity.ChatMessage;
import com.backend.domains.chat.repository.ChatActionRepository;
import com.backend.domains.chat.repository.ChatConversationRepository;
import com.backend.domains.chat.repository.ChatMessageRepository;
import com.backend.order.kis.enums.AccountType;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 대화 기억. LLM 은 상태가 없으므로 매 요청마다 "이 대화에서 무슨 일이 있었는지"를 다시 알려 줘야 한다.
 *
 * <ol>
 *   <li><b>최근 대화 창</b>: 글자 수 예산 안에서 가장 최근 메시지부터 원문 그대로 담는다.</li>
 *   <li><b>요약</b>: 창에서 밀려난 오래된 대화는 LLM 으로 요약해 대화에 저장하고, 시스템 프롬프트로 전달한다.</li>
 *   <li><b>구조화된 맥락</b>: 최근 다룬 종목, 대기 중/최근 처리된 확인 카드를 따로 뽑아 "그 종목", "방금 그 주문"을 풀게 한다.</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConversationMemory {

	public record Prepared(List<Message> window, String contextBlock) {
	}

	public record StockRef(String productNumber, String name, String market) {
	}

	private static final Set<String> STOCK_CARD_TYPES = Set.of("quote", "prediction", "candles", "order_result");
	private static final int FETCH_LIMIT = 80;
	private static final int MAX_STOCKS = 6;
	private static final int TRANSCRIPT_CAP = 24_000;
	private static final int PER_MESSAGE_CAP = 700;

	private static final String SUMMARY_SYSTEM = """
		당신은 대화 요약기입니다. 주식 어시스턴트와 사용자의 대화를 한국어 불릿 목록으로 간결하게 요약하세요.
		반드시 보존할 것: 사용자의 목표와 선호(투자 성향, 예산, 기간 등), 다룬 종목(이름·코드·시장),
		중요한 수치(가격·수량·금액·예측 방향), 실행/대기/거절/만료된 주문과 예약(종목, 수량, 계좌 유형, 결과),
		아직 답하지 못한 질문과 사용자가 요청한 후속 작업.
		인사말과 군더더기는 빼고, 확인되지 않은 내용을 지어내지 마세요. 요약문만 출력하세요.""";

	private final ChatConversationRepository conversations;
	private final ChatMessageRepository messages;
	private final ChatActionRepository actions;
	private final ChatMapper mapper;
	private final LlmGateway llm;
	private final AppProperties props;
	private final Json json;
	private final TransactionTemplate tx;

	private final Set<Long> summarizing = ConcurrentHashMap.newKeySet();

	// ─────────────────────────── 요청 준비 ───────────────────────────

	/** 이번 사용자 메시지(currentUserMessageId) 직전까지의 기억을 LLM 입력 형태로 만든다. */
	public Prepared prepare(Long conversationId, Long currentUserMessageId) {
		ChatConversation conv = conversations.findById(conversationId).orElse(null);
		long summarizedUntil = conv == null || conv.getSummarizedUntilId() == null ? 0L : conv.getSummarizedUntilId();

		List<ChatMessage> newestFirst = messages.findByConversationIdAndIdLessThanOrderByIdDesc(
			conversationId, currentUserMessageId, PageRequest.of(0, FETCH_LIMIT));
		List<ChatMessage> ascending = new ArrayList<>(newestFirst);
		Collections.reverse(ascending);

		List<ChatMessage> unsummarized = ascending.stream().filter(m -> m.getId() > summarizedUntil).toList();
		List<ChatMessage> selected = selectWindow(unsummarized, props.getLlm().getHistoryCharBudget(),
			props.getLlm().getHistoryLimit(), this::lengthOf);

		List<Message> window = new ArrayList<>();
		for (ChatMessage m : selected) {
			window.add(m.getRole() == ChatMessage.Role.USER
				? new UserMessage(m.getContent())
				: new AssistantMessage(assistantText(m)));
		}

		List<String> cardsNewestFirst = newestFirst.stream()
			.filter(m -> m.getRole() == ChatMessage.Role.ASSISTANT && m.getCardsJson() != null)
			.map(ChatMessage::getCardsJson).toList();
		List<StockRef> stocks = extractStocks(cardsNewestFirst, json.mapper(), MAX_STOCKS);
		List<Action> recentActions = actions.findByConversationIdOrderByIdDesc(conversationId, PageRequest.of(0, 5))
			.stream().map(mapper::action).toList();

		String summary = conv == null ? null : conv.getSummary();
		return new Prepared(window, renderBlock(summary, stocks, renderActions(recentActions)));
	}

	// ─────────────────────────── 요약 ───────────────────────────

	/**
	 * 창에서 밀려날 만큼 쌓인 오래된 대화를 요약해 저장한다. 응답이 끝난 뒤 백그라운드로 호출한다.
	 * 실패해도 다음 요청에서 다시 시도되므로 조용히 넘어간다.
	 */
	public void summarizeIfNeeded(Long conversationId) {
		if (!summarizing.add(conversationId)) {
			return;
		}
		try {
			ChatConversation conv = conversations.findById(conversationId).orElse(null);
			if (conv == null) {
				return;
			}
			long after = conv.getSummarizedUntilId() == null ? 0L : conv.getSummarizedUntilId();
			List<ChatMessage> pending = messages.findByConversationIdAndIdGreaterThanOrderByIdAsc(conversationId, after)
				.stream().filter(ConversationMemory::usable).toList();
			int keep = props.getLlm().getKeepRecentMessages();
			if (pending.size() <= keep) {
				return;
			}
			List<ChatMessage> candidates = pending.subList(0, pending.size() - keep);
			int total = candidates.stream().mapToInt(this::lengthOf).sum();
			if (total < props.getLlm().getSummaryTriggerChars()) {
				return;
			}

			StringBuilder transcript = new StringBuilder();
			Long until = null;
			for (ChatMessage m : candidates) {
				String line = (m.getRole() == ChatMessage.Role.USER ? "사용자: " : "어시스턴트: ")
					+ cap(m.getRole() == ChatMessage.Role.USER ? m.getContent() : assistantText(m), PER_MESSAGE_CAP) + "\n";
				if (transcript.length() + line.length() > TRANSCRIPT_CAP && until != null) {
					break;
				}
				transcript.append(line);
				until = m.getId();
			}

			String existing = conv.getSummary() == null || conv.getSummary().isBlank() ? "(없음)" : conv.getSummary();
			String prompt = "기존 요약:\n" + existing + "\n\n새로 추가된 대화:\n" + transcript
				+ "\n위 둘을 합쳐 갱신된 요약을 " + props.getLlm().getSummaryMaxChars() + "자 이내로 출력하세요.";
			String result = llm.complete(SUMMARY_SYSTEM, prompt);
			if (result == null || result.isBlank()) {
				return;
			}
			String trimmed = trimToLimit(result.trim(), props.getLlm().getSummaryMaxChars());
			final Long summarizedUntil = until;
			tx.executeWithoutResult(s -> conversations.findById(conversationId)
				.ifPresent(c -> c.applySummary(trimmed, summarizedUntil)));
			log.info("대화 {} 요약 갱신 (메시지 ~#{} 까지, {}자)", conversationId, until, trimmed.length());
		} catch (RuntimeException e) {
			log.info("대화 {} 요약 실패(다음 기회에 재시도): {}", conversationId, e.getMessage());
		} finally {
			summarizing.remove(conversationId);
		}
	}

	// ─────────────────────────── 메시지 → 텍스트 ───────────────────────────

	private int lengthOf(ChatMessage m) {
		return m.getRole() == ChatMessage.Role.USER ? len(m.getContent()) : assistantText(m).length();
	}

	/** 어시스턴트 메시지 + 사용한 도구의 한 줄 요약(수치가 답변에 안 드러난 경우를 대비). */
	String assistantText(ChatMessage m) {
		StringBuilder sb = new StringBuilder(m.getContent() == null ? "" : m.getContent());
		List<ToolCall> calls = json.read(m.getToolCallsJson(), new TypeReference<List<ToolCall>>() {}, List.of());
		StringBuilder note = new StringBuilder();
		for (ToolCall c : calls) {
			if ("DONE".equals(c.status()) && c.summary() != null && !c.summary().isBlank()) {
				note.append(note.length() == 0 ? "\n\n(사용한 도구: " : "; ").append(c.label()).append(" → ").append(c.summary());
			} else if ("ERROR".equals(c.status()) && c.summary() != null) {
				note.append(note.length() == 0 ? "\n\n(사용한 도구: " : "; ").append(c.label()).append(" → 실패: ").append(c.summary());
			}
		}
		if (note.length() > 0) {
			sb.append(note).append(")");
		}
		if (m.getStatus() == ChatMessage.Status.STOPPED) {
			sb.append("\n(사용자가 응답 생성을 중단함)");
		}
		return sb.toString();
	}

	static boolean usable(ChatMessage m) {
		if (m.getRole() == ChatMessage.Role.USER) {
			return m.getContent() != null && !m.getContent().isBlank();
		}
		return m.getStatus() != ChatMessage.Status.STREAMING && m.getContent() != null && !m.getContent().isBlank();
	}

	// ─────────────────────────── 순수 함수(테스트 대상) ───────────────────────────

	/**
	 * 오래된 순으로 정렬된 메시지에서 글자 수 예산 안의 최신 메시지들을 고른다.
	 * 항상 최소 2개는 남기고, 첫 메시지는 USER 가 되도록 맞춘다(Claude 는 첫 메시지가 user 여야 한다).
	 */
	public static List<ChatMessage> selectWindow(List<ChatMessage> ascending, int charBudget, int maxMessages,
		java.util.function.ToIntFunction<ChatMessage> length) {
		List<ChatMessage> picked = new ArrayList<>();
		int used = 0;
		for (int i = ascending.size() - 1; i >= 0; i--) {
			ChatMessage m = ascending.get(i);
			if (!usable(m)) {
				continue;
			}
			int len = length.applyAsInt(m);
			boolean overBudget = used + len > charBudget || picked.size() >= maxMessages;
			if (overBudget && picked.size() >= 2) {
				break;
			}
			picked.add(m);
			used += len;
		}
		Collections.reverse(picked);
		while (!picked.isEmpty() && picked.get(0).getRole() == ChatMessage.Role.ASSISTANT) {
			picked.remove(0);
		}
		return picked;
	}

	/** 카드(JSON)에서 최근 다룬 종목을 최신순으로 뽑는다. */
	public static List<StockRef> extractStocks(List<String> cardsJsonNewestFirst, ObjectMapper mapper, int limit) {
		Map<String, StockRef> found = new LinkedHashMap<>();
		for (String cardsJson : cardsJsonNewestFirst) {
			if (cardsJson == null || cardsJson.isBlank()) {
				continue;
			}
			JsonNode cards;
			try {
				cards = mapper.readTree(cardsJson);
			} catch (Exception e) {
				continue;
			}
			if (!cards.isArray()) {
				continue;
			}
			// 같은 메시지 안에서는 뒤에 있는 카드가 더 최근이다.
			for (int i = cards.size() - 1; i >= 0; i--) {
				JsonNode card = cards.get(i);
				String type = card.path("type").asText("");
				JsonNode data = card.path("data");
				if (STOCK_CARD_TYPES.contains(type)) {
					addStock(found, data);
				} else if (type.equals("recommendations") && data.isArray()) {
					for (int k = 0; k < Math.min(3, data.size()); k++) {
						addStock(found, data.get(k));
					}
				}
				if (found.size() >= limit) {
					return new ArrayList<>(found.values());
				}
			}
		}
		return new ArrayList<>(found.values());
	}

	private static void addStock(Map<String, StockRef> found, JsonNode node) {
		String code = node.path("productNumber").asText("");
		if (code.isBlank()) {
			return;
		}
		String market = node.path("market").asText("");
		found.putIfAbsent(market + ":" + code, new StockRef(code, node.path("name").asText(code), market));
	}

	public static List<String> renderActions(List<Action> recent) {
		List<String> lines = new ArrayList<>();
		for (Action a : recent) {
			String kind = a.accountType() == AccountType.REAL_TRADE ? "실전" : a.accountType() == AccountType.PAPER_TRADE ? "모의" : "";
			String line = "- " + a.title() + " — " + statusKo(a.status()) + (kind.isEmpty() ? "" : " (" + kind + " 계좌)");
			if (a.result() != null && a.result().message() != null && !a.result().message().isBlank()) {
				line += ": " + cap(a.result().message(), 120);
			}
			lines.add(line);
		}
		return lines;
	}

	private static String statusKo(String status) {
		return switch (status) {
			case "PENDING" -> "사용자 승인 대기 중(승인 전에는 실행되지 않음)";
			case "EXECUTED" -> "승인되어 실행됨";
			case "REJECTED" -> "사용자가 거절함";
			case "FAILED" -> "실행 실패";
			case "EXPIRED" -> "유효시간이 지나 만료됨";
			case "APPROVED" -> "처리 중";
			default -> status;
		};
	}

	/** 시스템 프롬프트에 붙일 '이 대화의 기억' 블록. 비어 있으면 빈 문자열. */
	public static String renderBlock(String summary, List<StockRef> stocks, List<String> actionLines) {
		boolean hasSummary = summary != null && !summary.isBlank();
		if (!hasSummary && stocks.isEmpty() && actionLines.isEmpty()) {
			return "";
		}
		StringBuilder sb = new StringBuilder("## 이 대화의 기억\n");
		sb.append("사용자가 '그거', '그 종목', '아까', '방금 그 주문' 처럼 말하면 아래 내용과 이전 메시지로 해석하세요.\n");
		if (hasSummary) {
			sb.append("\n### 앞부분 요약\n").append(summary.trim()).append('\n');
		}
		if (!stocks.isEmpty()) {
			sb.append("\n### 최근 다룬 종목 (최신순)\n");
			for (StockRef s : stocks) {
				sb.append("- ").append(s.name()).append(" (").append(s.productNumber());
				if (!s.market().isBlank()) {
					sb.append(", ").append(s.market());
				}
				sb.append(")\n");
			}
		}
		if (!actionLines.isEmpty()) {
			sb.append("\n### 주문·예약 확인 카드 현황 (최신순)\n");
			actionLines.forEach(l -> sb.append(l).append('\n'));
		}
		return sb.toString();
	}

	static String trimToLimit(String text, int max) {
		if (text.length() <= max) {
			return text;
		}
		int cut = text.lastIndexOf('\n', max);
		return text.substring(0, cut > max / 2 ? cut : max).trim();
	}

	private static String cap(String s, int max) {
		if (s == null) {
			return "";
		}
		String flat = s.replaceAll("\\s+", " ").trim();
		return flat.length() <= max ? flat : flat.substring(0, max) + "…";
	}

	private static int len(String s) {
		return s == null ? 0 : s.length();
	}
}
