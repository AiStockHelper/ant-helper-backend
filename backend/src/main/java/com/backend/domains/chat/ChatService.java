package com.backend.domains.chat;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backend.common.dto.PageResponse;
import com.backend.common.exception.ApiException;
import com.backend.common.exception.ErrorCode;
import com.backend.domains.account.AccountService;
import com.backend.domains.chat.dto.ChatDtos.Action;
import com.backend.domains.chat.dto.ChatDtos.Conversation;
import com.backend.domains.chat.dto.ChatDtos.ConversationDetail;
import com.backend.domains.chat.dto.ChatDtos.Message;
import com.backend.domains.chat.dto.ChatDtos.UpdateConversationRequest;
import com.backend.domains.chat.entity.ChatAction;
import com.backend.domains.chat.entity.ChatConversation;
import com.backend.domains.chat.entity.ChatMessage;
import com.backend.domains.chat.repository.ChatActionRepository;
import com.backend.domains.chat.repository.ChatConversationRepository;
import com.backend.domains.chat.repository.ChatMessageRepository;

import lombok.RequiredArgsConstructor;

/** 대화 목록/조회/수정/삭제 (ChatGPT 웹의 사이드바 기능에 해당). */
@Service
@RequiredArgsConstructor
public class ChatService {

	private final ChatConversationRepository conversations;
	private final ChatMessageRepository messages;
	private final ChatActionRepository actions;
	private final ChatMapper mapper;
	private final AccountService accountService;

	@Transactional(readOnly = true)
	public PageResponse<Conversation> list(Long memberId, String q, int page, int size) {
		PageRequest pr = PageRequest.of(Math.max(page, 0), Math.max(1, Math.min(size, 100)));
		Page<ChatConversation> result = (q == null || q.isBlank())
			? conversations.findAllForMember(memberId, pr)
			: conversations.search(memberId, q.trim(), pr);
		return PageResponse.of(result.map(mapper::conversation));
	}

	@Transactional
	public Conversation create(Long memberId, Long accountId) {
		Long resolved = accountId;
		if (accountId != null) {
			accountService.requireOwned(memberId, accountId);
		} else {
			// 계좌가 없어도 대화는 만들 수 있다(시세·주문 도구를 쓸 때 계좌 등록을 안내한다).
			resolved = accountService.findDefault(memberId).map(a -> a.getId()).orElse(null);
		}
		return mapper.conversation(conversations.save(new ChatConversation(memberId, resolved)));
	}

	@Transactional(readOnly = true)
	public ConversationDetail detail(Long memberId, Long id) {
		ChatConversation c = require(memberId, id);
		List<ChatMessage> list = messages.findByConversationIdOrderByIdAsc(id);
		List<Long> ids = list.stream().map(ChatMessage::getId).toList();
		List<ChatAction> acts = ids.isEmpty() ? List.of() : actions.findByMessageIdIn(ids);
		Map<Long, List<Action>> byMessage = mapper.actionsByMessage(ids, acts);
		List<Message> dto = list.stream().map(m -> mapper.message(m, byMessage.get(m.getId()))).collect(Collectors.toList());
		return new ConversationDetail(c.getId(), c.getTitle(), c.isPinned(), c.getAccountId(), c.getLastMessagePreview(),
			mapper.conversation(c).createdAt(), mapper.conversation(c).updatedAt(), dto);
	}

	@Transactional
	public Conversation update(Long memberId, Long id, UpdateConversationRequest req) {
		ChatConversation c = require(memberId, id);
		if (req.title() != null && !req.title().isBlank()) {
			c.rename(req.title().trim());
		}
		if (req.pinned() != null) {
			c.pin(req.pinned());
		}
		if (req.accountId() != null) {
			accountService.requireOwned(memberId, req.accountId());
			c.useAccount(req.accountId());
		}
		return mapper.conversation(c);
	}

	@Transactional
	public void delete(Long memberId, Long id) {
		require(memberId, id);
		actions.deleteByConversation(id);
		messages.deleteByConversation(id);
		conversations.deleteById(id);
	}

	@Transactional
	public void deleteAll(Long memberId) {
		actions.deleteByMember(memberId);
		messages.deleteByMember(memberId);
		conversations.deleteAllByMemberId(memberId);
	}

	public ChatConversation require(Long memberId, Long id) {
		return conversations.findByIdAndMemberId(id, memberId)
			.orElseThrow(() -> ApiException.from(ErrorCode.CONVERSATION_NOT_FOUND));
	}
}
