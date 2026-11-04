package com.backend.domains.chat.tool;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import com.backend.domains.chat.dto.ChatDtos.Action;
import com.backend.domains.chat.dto.ChatDtos.Card;
import com.backend.domains.chat.dto.ChatDtos.ToolCall;
import com.backend.domains.member.domain.MemberAccount;
import com.backend.market.dto.MarketDtos.Recommendation;

/**
 * 한 번의 AI 실행 동안 도구들이 공유하는 컨텍스트.
 * memberId/accountId 는 서버가 채우며 LLM 은 바꿀 수 없다.
 * 사용자가 보고 있는 채팅과, 사람 없이 돌아가는 자동 실행(agent) 두 가지로 쓰인다.
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
	private final AtomicInteger toolCalls = new AtomicInteger();
	private final AtomicInteger ordersAccepted = new AtomicInteger();

	private volatile java.util.List<Recommendation> candidates = java.util.List.of();
	private volatile java.util.Set<String> modelPickSymbols = java.util.Set.of();

	private volatile Long agentTaskId;
	private volatile boolean autonomous;
	private volatile int orderCallLimit = 5;
	private volatile int toolCallLimit;

	public ChatSession(Long memberId, Long conversationId, Long assistantMessageId, Sink sink,
		Supplier<MemberAccount> accountSupplier) {
		this.memberId = memberId;
		this.conversationId = conversationId;
		this.assistantMessageId = assistantMessageId;
		this.sink = sink;
		this.accountSupplier = accountSupplier;
	}

	/** 자동 실행(사람 없음) 컨텍스트로 전환한다. */
	public ChatSession bindAgent(Long taskId, boolean autonomous, int toolCallLimit, int orderCallLimit) {
		this.agentTaskId = taskId;
		this.autonomous = autonomous;
		this.toolCallLimit = toolCallLimit;
		this.orderCallLimit = orderCallLimit;
		return this;
	}

	/** 방금 모델이 분석한 추천 후보 풀과 그중 모델 추천으로 보여준 종목들. AI 의견 카드가 모델 추천과의 일치 여부를 표시하는 데 쓴다. */
	public void rememberCandidates(java.util.List<Recommendation> pool, java.util.List<Recommendation> picks) {
		java.util.LinkedHashMap<String, Recommendation> all = new java.util.LinkedHashMap<>();
		for (Recommendation r : picks) {
			all.putIfAbsent(r.productNumber().toUpperCase(), r);
		}
		for (Recommendation r : pool) {
			all.putIfAbsent(r.productNumber().toUpperCase(), r);
		}
		this.candidates = java.util.List.copyOf(all.values());
		this.modelPickSymbols = picks.stream().map(r -> r.productNumber().toUpperCase()).collect(java.util.stream.Collectors.toSet());
	}

	public java.util.List<Recommendation> candidates() {
		return candidates;
	}

	public boolean isModelPick(String symbol) {
		return symbol != null && modelPickSymbols.contains(symbol.toUpperCase());
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

	/** 자동 실행 중이면 그 작업의 ID, 사용자가 보고 있는 채팅이면 null */
	public Long agentTaskId() {
		return agentTaskId;
	}

	/** 사람의 확인 없이 주문을 낼 수 있는 실행인가 */
	public boolean autonomous() {
		return autonomous;
	}

	/** 이 계좌가 선택된 대화/작업의 계좌. 계좌가 하나도 없으면 NO_ACCOUNT_SELECTED 예외. */
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
		return orderCalls.incrementAndGet() <= orderCallLimit;
	}

	/** 자동 실행에서 도구 호출이 폭주하지 않게 막는다. 한도가 0이면 제한 없음. */
	public boolean allowToolCall() {
		return toolCallLimit <= 0 || toolCalls.incrementAndGet() <= toolCallLimit;
	}

	public int toolCallLimit() {
		return toolCallLimit;
	}

	public void orderAccepted() {
		ordersAccepted.incrementAndGet();
	}

	/** 이번 실행에서 실제로 접수된 주문 수(이미 주문이 나갔다면 다른 모델로 재시도하면 안 된다). */
	public int ordersAccepted() {
		return ordersAccepted.get();
	}
}
