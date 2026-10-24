package com.backend.domains.chat;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.backend.common.util.Json;
import com.backend.common.util.TimeUtil;
import com.backend.domains.chat.dto.ChatDtos.Action;
import com.backend.domains.chat.dto.ChatDtos.ActionResult;
import com.backend.domains.chat.dto.ChatDtos.Card;
import com.backend.domains.chat.dto.ChatDtos.Conversation;
import com.backend.domains.chat.dto.ChatDtos.Detail;
import com.backend.domains.chat.dto.ChatDtos.Message;
import com.backend.domains.chat.dto.ChatDtos.ScheduledTaskDto;
import com.backend.domains.chat.dto.ChatDtos.ToolCall;
import com.backend.domains.chat.entity.ChatAction;
import com.backend.domains.chat.entity.ChatConversation;
import com.backend.domains.chat.entity.ChatMessage;
import com.backend.domains.member.domain.MemberAccount;
import com.backend.domains.member.repository.MemberAccountRepository;
import com.backend.domains.schedule.ScheduledTask;
import com.backend.order.kis.enums.AccountType;
import com.fasterxml.jackson.core.type.TypeReference;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class ChatMapper {

	private final Json json;
	private final MemberAccountRepository accountRepository;

	public Conversation conversation(ChatConversation c) {
		return new Conversation(c.getId(), c.getTitle(), c.isPinned(), c.getAccountId(), c.getLastMessagePreview(),
			TimeUtil.kst(c.getCreatedAt()), TimeUtil.kst(c.getUpdatedAt()));
	}

	public Message message(ChatMessage m, List<Action> actions) {
		return new Message(m.getId(), m.getRole().name(), m.getContent() == null ? "" : m.getContent(),
			m.getStatus().name(),
			json.read(m.getToolCallsJson(), new TypeReference<List<ToolCall>>() {}, List.of()),
			json.read(m.getCardsJson(), new TypeReference<List<Card>>() {}, List.of()),
			actions == null ? List.of() : actions, m.getModel(), TimeUtil.kst(m.getCreatedAt()));
	}

	public Action action(ChatAction a) {
		Optional<MemberAccount> account = accountRepository.findById(a.getAccountId());
		return action(a, account.orElse(null));
	}

	public Action action(ChatAction a, MemberAccount account) {
		ChatAction.Status status = a.getStatus();
		if (status == ChatAction.Status.PENDING && a.isExpired()) {
			status = ChatAction.Status.EXPIRED;
		}
		return new Action(a.getId(), a.getKind().name(), status.name(), a.getTitle(), a.getSummary(),
			a.getAccountId(), account == null ? null : account.getAccountType(),
			account == null ? "삭제된 계좌" : account.displayName(),
			json.read(a.getDetailsJson(), new TypeReference<List<Detail>>() {}, List.of()),
			json.read(a.getResultJson(), ActionResult.class),
			TimeUtil.kst(a.getExpiresAt()), TimeUtil.kst(a.getCreatedAt()));
	}

	/** 메시지 ID → 해당 메시지에 붙은 확인 카드들 */
	public Map<Long, List<Action>> actionsByMessage(Collection<Long> messageIds, Collection<ChatAction> actions) {
		Map<Long, MemberAccount> accounts = accountRepository
			.findAllById(actions.stream().map(ChatAction::getAccountId).distinct().toList())
			.stream().collect(Collectors.toMap(MemberAccount::getId, x -> x));
		return actions.stream()
			.filter(a -> a.getMessageId() != null && messageIds.contains(a.getMessageId()))
			.collect(Collectors.groupingBy(ChatAction::getMessageId,
				Collectors.mapping(a -> action(a, accounts.get(a.getAccountId())), Collectors.toList())));
	}

	public ScheduledTaskDto task(ScheduledTask t) {
		AccountType type = accountRepository.findById(t.getAccountId()).map(MemberAccount::getAccountType).orElse(null);
		Map<String, Object> params = json.read(t.getParamsJson(), new TypeReference<Map<String, Object>>() {}, Map.of());
		return new ScheduledTaskDto(t.getId(), t.getAccountId(), type, t.getKind().name(), t.getTitle(),
			TimeUtil.kst(t.getRunAt()), t.getStatus().name(), params, t.getResultMessage(),
			TimeUtil.kst(t.getExecutedAt()), TimeUtil.kst(t.getCreatedAt()));
	}
}
