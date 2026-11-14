package com.backend.market.queue;

/** 주문이 어디서 왔는지. 실패 후 다시 시도할지, 결과를 어디에 알릴지가 여기에 달려 있다. */
public record OrderSource(Type type, Long ref, Long conversationId, String reason) {

	public enum Type {
		/** 사용자가 채팅의 확인 카드를 승인한 주문 */
		CHAT_ACTION,
		/** 자동 실행(에이전트)이 낸 주문. 판단 시점이 지나면 의미가 달라지므로 스케줄러가 대신 다시 보내지 않는다. */
		AGENT,
		MANUAL
	}

	public static OrderSource manual() {
		return new OrderSource(Type.MANUAL, null, null, null);
	}

	public static OrderSource chat(Long actionId, Long conversationId) {
		return new OrderSource(Type.CHAT_ACTION, actionId, conversationId, null);
	}

	public static OrderSource agent(Long taskId, Long conversationId, String reason) {
		return new OrderSource(Type.AGENT, taskId, conversationId, reason);
	}
}
