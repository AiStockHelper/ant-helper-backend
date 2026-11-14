package com.backend.domains.agent;

import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.backend.common.properties.AppProperties;
import com.backend.common.util.Json;
import com.backend.domains.chat.dto.ChatDtos.Card;
import com.backend.domains.chat.entity.ChatConversation;
import com.backend.domains.chat.entity.ChatMessage;
import com.backend.domains.chat.repository.ChatConversationRepository;
import com.backend.domains.chat.repository.ChatMessageRepository;
import com.backend.domains.email.EmailUtil;
import com.backend.domains.member.domain.MemberAccount;
import com.backend.domains.member.repository.MemberRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 자동 실행 결과를 사용자에게 알린다: 연결된 대화에 메시지로 남기고, 중요한 일은 이메일로 보낸다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentReporter {

	private final ChatConversationRepository conversations;
	private final ChatMessageRepository messages;
	private final AgentTaskRepository tasks;
	private final MemberRepository members;
	private final EmailUtil emailUtil;
	private final AppProperties props;
	private final Json json;
	private final TransactionTemplate tx;

	public void post(AgentTask task, String title, String body, List<Card> cards) {
		try {
			tx.executeWithoutResult(s -> {
				ChatConversation conv = task.getConversationId() == null ? null
					: conversations.findById(task.getConversationId()).orElse(null);
				if (conv == null) {
					// 사용자가 대화를 지웠거나 처음부터 없던 경우: 새 대화를 만들어 계속 이어서 남긴다.
					conv = conversations.save(new ChatConversation(task.getMemberId(), task.getAccountId()));
					conv.rename("🤖 " + task.getTitle());
					final Long newId = conv.getId();
					tasks.findById(task.getId()).ifPresent(t -> t.attachConversation(newId));
				}
				ChatMessage m = ChatMessage.assistantPlaceholder(conv.getId(), task.getMemberId());
				m.finish(title + "\n\n" + body, ChatMessage.Status.COMPLETE, null, json.write(cards), "agent");
				messages.save(m);
				conv.updatePreview(body);
				conv.touch();
			});
		} catch (RuntimeException e) {
			log.warn("자동 실행 결과를 대화에 남기지 못했습니다 task={}: {}", task.getId(), e.getMessage());
		}
	}

	public void email(AgentTask task, MemberAccount account, String subject, String body) {
		if (!props.getAgent().isNotifyEmail()) {
			return;
		}
		try {
			members.findById(task.getMemberId()).ifPresent(m -> emailUtil.sendEmail(m.getEmail(), "[Ant Helper] " + subject,
				body + "\n\n작업: " + task.getTitle() + "\n계좌: " + account.displayName()
					+ (account.isPaper() ? " (모의)" : " (실전)") + "\n\n자세한 내용은 앱의 '스킬'·'내 계좌 > 자동화'에서 확인하세요."));
		} catch (RuntimeException e) {
			log.warn("자동 실행 알림 메일을 보내지 못했습니다 task={}: {}", task.getId(), e.getMessage());
		}
	}
}
