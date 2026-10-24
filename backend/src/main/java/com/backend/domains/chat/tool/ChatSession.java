package com.backend.domains.chat.tool;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import com.backend.domains.chat.dto.ChatDtos.Action;
import com.backend.domains.chat.dto.ChatDtos.Card;
import com.backend.domains.chat.dto.ChatDtos.ToolCall;
import com.backend.domains.member.domain.MemberAccount;

/**
 * 한 번의 AI 응답 동안 도구들이 공유하는 컨텍스트.
 * memberId/accountId 는 서버가 채우며 LLM 은 바꿀 수 없다.
 */
public final class ChatSession {

	public interface Sink {
		void tool(ToolCall call);

		void card(Card card);

		void action(Action action);
	}

	private final Long memberId;
	private final Long conversationId;
	private final Long assistantMessageId;
	private final Sink sink;
	private final Supplier<MemberAccount> accountSupplier;

	private volatile MemberAccount account;
	private volatile String summary;
	private final AtomicInteger orderCalls = new AtomicInteger();

	public ChatSession(Long memberId, Long conversationId, Long assistantMessageId, Sink sink,
		Supplier<MemberAccount> accountSupplier) {
		this.memberId = memberId;
		this.conversationId = conversationId;
		this.assistantMessageId = assistantMessageId;
		this.sink = sink;
		this.accountSupplier = accountSupplier;
	}

	public Long memberId() {
		return memberId;
	}

	public Long conversationId() {
		return conversationId;
	}

	public Long assistantMessageId() {
		return assistantMessageId;
	}

	/** 이 대화에서 선택된 계좌(없으면 기본 계좌). 계좌가 하나도 없으면 NO_ACCOUNT_SELECTED 예외. */
	public MemberAccount account() {
		MemberAccount a = account;
		if (a == null) {
			synchronized (this) {
				if (account == null) {
					account = accountSupplier.get();
				}
				a = account;
			}
		}
		return a;
	}

	public void card(String type, Object data) {
		sink.card(new Card(type, data));
	}

	public void action(Action action) {
		sink.action(action);
	}

	public void tool(ToolCall call) {
		sink.tool(call);
	}

	public void summary(String text) {
		this.summary = text;
	}

	public String takeSummary() {
		String s = summary;
		summary = null;
		return s;
	}

	/** 한 응답에서 주문성 도구를 과도하게 호출하지 못하게 막는다. */
	public boolean allowOrderCall() {
		return orderCalls.incrementAndGet() <= 5;
	}
}
