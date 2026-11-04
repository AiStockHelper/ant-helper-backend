package com.backend.domains.chat.memory;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backend.common.util.TimeUtil;
import com.backend.domains.chat.entity.ChatConversation;
import com.backend.domains.chat.entity.ChatMessage;
import com.backend.domains.chat.repository.ChatConversationRepository;
import com.backend.domains.chat.repository.ChatMessageRepository;

import lombok.RequiredArgsConstructor;

/**
 * 사용자의 지난 대화(다른 대화 포함)에서 내용을 찾는다. AI 도구 search_chat_history 가 사용한다.
 *
 * 지금은 키워드(부분 일치) 검색이다. 의미 기반 검색(임베딩 + 벡터 저장소)으로 바꾸려면 이 클래스의
 * {@link #search} 만 교체하면 되고, 호출하는 도구/프롬프트는 그대로 쓸 수 있다.
 */
@Service
@RequiredArgsConstructor
public class ChatHistorySearchService {

	public record Hit(Long conversationId, String conversationTitle, String role, String snippet, OffsetDateTime at) {
	}

	public record RecentConversation(Long conversationId, String title, String lastMessagePreview, OffsetDateTime updatedAt) {
	}

	private static final int SNIPPET_BEFORE = 70;
	private static final int SNIPPET_AFTER = 170;

	private final ChatMessageRepository messages;
	private final ChatConversationRepository conversations;

	@Transactional(readOnly = true)
	public List<Hit> search(Long memberId, Long excludeConversationId, String query, int limit) {
		if (query == null || query.isBlank()) {
			return List.of();
		}
		String q = query.trim();
		List<ChatMessage> found = messages.searchByMember(memberId, excludeConversationId == null ? -1L : excludeConversationId, q, PageRequest.of(0, Math.max(1, Math.min(limit, 10))));
		Map<Long, ChatConversation> byId = conversations.findAllById(
			found.stream().map(ChatMessage::getConversationId).distinct().toList())
			.stream().collect(Collectors.toMap(ChatConversation::getId, Function.identity()));
		return found.stream().map(m -> {
			ChatConversation c = byId.get(m.getConversationId());
			return new Hit(m.getConversationId(), c == null ? "" : c.getTitle(), m.getRole().name(),
				snippet(m.getContent(), q), TimeUtil.kst(m.getCreatedAt()));
		}).toList();
	}

	@Transactional(readOnly = true)
	public List<RecentConversation> recent(Long memberId, Long excludeConversationId, int limit) {
		return conversations.findAllForMember(memberId, PageRequest.of(0, Math.max(1, Math.min(limit, 10)) + 1)).stream()
			.filter(c -> !c.getId().equals(excludeConversationId))
			.limit(Math.max(1, Math.min(limit, 10)))
			.map(c -> new RecentConversation(c.getId(), c.getTitle(), c.getLastMessagePreview(), TimeUtil.kst(c.getUpdatedAt())))
			.toList();
	}

	/** 일치한 위치 주변만 잘라서 보여 준다. */
	static String snippet(String content, String query) {
		if (content == null) {
			return "";
		}
		String flat = content.replaceAll("\\s+", " ").trim();
		int idx = flat.toLowerCase(Locale.ROOT).indexOf(query.toLowerCase(Locale.ROOT));
		if (idx < 0) {
			return flat.length() <= SNIPPET_BEFORE + SNIPPET_AFTER ? flat : flat.substring(0, SNIPPET_BEFORE + SNIPPET_AFTER) + "…";
		}
		int from = Math.max(0, idx - SNIPPET_BEFORE);
		int to = Math.min(flat.length(), idx + query.length() + SNIPPET_AFTER);
		return (from > 0 ? "…" : "") + flat.substring(from, to) + (to < flat.length() ? "…" : "");
	}
}
