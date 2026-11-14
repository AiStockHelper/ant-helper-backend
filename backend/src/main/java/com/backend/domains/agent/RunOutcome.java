package com.backend.domains.agent;

import java.util.List;

import com.backend.domains.chat.dto.ChatDtos.Card;

/** 자동 실행 한 번의 결과. */
public record RunOutcome(
	AgentRun.Status status, String summary, List<String> events, List<Card> cards, String model,
	/** true 이면 아무 일도 없었던 점검이라 대화·기록을 남기지 않는다. */
	boolean quiet,
	/** 값이 있으면 실행 후 작업을 이 사유로 일시정지한다. */
	String pauseReason,
	int ordersAccepted
) {

	public static RunOutcome skipped(String why) {
		return new RunOutcome(AgentRun.Status.SKIPPED, why, List.of(), List.of(), null, false, null, 0);
	}

	public static RunOutcome failed(String why) {
		return new RunOutcome(AgentRun.Status.FAILED, why, List.of(), List.of(), null, false, null, 0);
	}
}
