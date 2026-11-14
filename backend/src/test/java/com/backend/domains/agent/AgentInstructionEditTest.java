package com.backend.domains.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.backend.common.exception.ApiException;

class AgentInstructionEditTest {

	@Test
	void replaceUsesTheNewTextOnly() {
		assertThat(AgentTaskService.merge("기존 정리본", "  새 정리본  ", "REPLACE")).isEqualTo("새 정리본");
		assertThat(AgentTaskService.merge("기존 정리본", "새 정리본", null)).isEqualTo("새 정리본");
	}

	@Test
	void appendKeepsTheOldTextAndMarksTheAddition() {
		String merged = AgentTaskService.merge("목표: 삼성전자 매수", "손절은 -3%로", "append");
		assertThat(merged).startsWith("목표: 삼성전자 매수").contains("[추가 지시 ").endsWith("손절은 -3%로");
	}

	@Test
	void emptyOrTooLongIsRejected() {
		assertThatThrownBy(() -> AgentTaskService.merge("x", "   ", "REPLACE")).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> AgentTaskService.merge("x", "가".repeat(4001), "REPLACE")).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> AgentTaskService.merge("가".repeat(3000), "나".repeat(1500), "APPEND")).isInstanceOf(ApiException.class);
	}

	@Test
	void reviseKeepsHistoryAndTitleRules() {
		AgentTask t = new AgentTask(1L, 2L, 3L, null, "제목", "옛 지시", null, "{}", null, true, null, null, null, 0, AgentTask.CreatedBy.USER);
		t.revise("새 제목", "새 지시", "[{\"at\":\"x\"}]");
		assertThat(t.getTitle()).isEqualTo("새 제목");
		assertThat(t.getInstruction()).isEqualTo("새 지시");
		assertThat(t.getInstructionHistory()).contains("\"at\"");
		t.revise("  ", "또 새 지시", "[]");
		assertThat(t.getTitle()).isEqualTo("새 제목");   // 빈 제목은 무시
		assertThat(t.getInstruction()).isEqualTo("또 새 지시");
	}
}