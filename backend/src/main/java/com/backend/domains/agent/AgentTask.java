package com.backend.domains.agent;

import java.time.Instant;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 자동 실행 작업. 일정(schedule)에 따라 서버가 깨워서 실행한다.
 * skillId 가 있으면 정해진 전략 코드(스킬)가, 없으면 instruction 을 AI 가 읽고 도구로 수행한다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "agent_task", indexes = {
	@Index(name = "idx_agent_due", columnList = "status, next_run_at"),
	@Index(name = "idx_agent_member", columnList = "member_id"),
	@Index(name = "idx_agent_ledger", columnList = "ledger_id")
})
public class AgentTask {

	public enum Status {ACTIVE, RUNNING, PAUSED, DONE, CANCELED}

	public enum CreatedBy {USER, AGENT}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "member_id", nullable = false)
	private Long memberId;

	@Column(name = "account_id", nullable = false)
	private Long accountId;

	/** 실행 결과를 남기는 대화 */
	@Column(name = "conversation_id")
	private Long conversationId;

	@Column(name = "skill_id", length = 60)
	private String skillId;

	@Column(name = "title", nullable = false, length = 200)
	private String title;

	@Column(name = "instruction", columnDefinition = "LONGTEXT")
	private String instruction;

	/** 지시(정리본)를 고칠 때마다 이전 내용을 남긴다. JSON 배열 [{at, by, title, instruction}], 최근 20개. */
	@Column(name = "instruction_history", columnDefinition = "LONGTEXT")
	private String instructionHistory;

	@Column(name = "config_json", columnDefinition = "LONGTEXT")
	private String configJson;

	@Column(name = "schedule_json", columnDefinition = "LONGTEXT", nullable = false)
	private String scheduleJson;

	@Column(name = "limits_json", columnDefinition = "LONGTEXT")
	private String limitsJson;

	/** 스킬이 실행 사이에 넘겨 받는 상태(JSON) */
	@Column(name = "state_json", columnDefinition = "LONGTEXT")
	private String stateJson;

	/** AI 작업이 스스로 남기는 메모 */
	@Column(name = "memory", columnDefinition = "LONGTEXT")
	private String memory;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 12)
	private Status status;

	/** true 이면 실전 계좌에서도 사람 확인 없이 한도 안에서 주문한다(생성 시 사용자가 명시적으로 승인). */
	@Column(name = "autonomous", nullable = false)
	private boolean autonomous;

	@Column(name = "next_run_at")
	private Instant nextRunAt;

	@Column(name = "last_run_at")
	private Instant lastRunAt;

	@Column(name = "last_result", columnDefinition = "LONGTEXT")
	private String lastResult;

	@Column(name = "run_count", nullable = false)
	private int runCount;

	@Column(name = "fail_count", nullable = false)
	private int failCount;

	@Column(name = "paused_reason", length = 500)
	private String pausedReason;

	@Column(name = "parent_task_id")
	private Long parentTaskId;

	/** 예산·포지션을 공유하는 묶음의 대표 작업 ID. 하위 작업은 부모의 ledgerId 를 쓴다. */
	@Column(name = "ledger_id")
	private Long ledgerId;

	@Column(name = "depth", nullable = false)
	private int depth;

	@Enumerated(EnumType.STRING)
	@Column(name = "created_by", nullable = false, length = 8)
	private CreatedBy createdBy;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;

	public AgentTask(Long memberId, Long accountId, Long conversationId, String skillId, String title, String instruction,
		String configJson, String scheduleJson, String limitsJson, boolean autonomous, Instant nextRunAt,
		Long parentTaskId, Long ledgerId, int depth, CreatedBy createdBy) {
		this.memberId = memberId;
		this.accountId = accountId;
		this.conversationId = conversationId;
		this.skillId = skillId;
		this.title = title;
		this.instruction = instruction;
		this.configJson = configJson;
		this.scheduleJson = scheduleJson;
		this.limitsJson = limitsJson;
		this.autonomous = autonomous;
		this.nextRunAt = nextRunAt;
		this.parentTaskId = parentTaskId;
		this.ledgerId = ledgerId;
		this.depth = depth;
		this.createdBy = createdBy;
		this.status = Status.ACTIVE;
	}

	@PrePersist
	void onCreate() {
		LocalDateTime now = LocalDateTime.now();
		createdAt = now;
		updatedAt = now;
	}

	@PreUpdate
	void onUpdate() {
		updatedAt = LocalDateTime.now();
	}

	public void assignLedger(Long ledgerId) {
		this.ledgerId = ledgerId;
	}

	public void attachConversation(Long conversationId) {
		this.conversationId = conversationId;
	}

	/** 제목·지시를 새 내용으로 바꾸고, 바꾸기 전 내용을 이력(historyJson)으로 남긴다. */
	public void revise(String newTitle, String newInstruction, String historyJson) {
		if (newTitle != null && !newTitle.isBlank()) {
			this.title = newTitle.length() > 200 ? newTitle.substring(0, 200) : newTitle;
		}
		this.instruction = newInstruction;
		this.instructionHistory = historyJson;
	}

	/** 제목만 바꾼다. 비어 있으면 무시한다. */
	public void rename(String newTitle) {
		if (newTitle != null && !newTitle.isBlank()) {
			String t = newTitle.trim();
			this.title = t.length() > 200 ? t.substring(0, 200) : t;
		}
	}

	/** 설정·일정·한도를 바꾼다. null 인 값은 그대로 둔다. 상태·장부·실행 이력은 건드리지 않는다. */
	public void changeSettings(String configJson, String scheduleJson, String limitsJson) {
		if (configJson != null) {
			this.configJson = configJson;
		}
		if (scheduleJson != null) {
			this.scheduleJson = scheduleJson;
		}
		if (limitsJson != null) {
			this.limitsJson = limitsJson;
		}
	}

	public void saveMemory(String memory) {
		this.memory = memory;
	}

	public void saveState(String stateJson) {
		this.stateJson = stateJson;
	}

	public void pause(String reason) {
		this.status = Status.PAUSED;
		this.pausedReason = reason;
	}

	public void resume(Instant nextRunAt) {
		this.status = Status.ACTIVE;
		this.pausedReason = null;
		this.failCount = 0;
		this.nextRunAt = nextRunAt;
	}

	public void cancel(String reason) {
		this.status = Status.CANCELED;
		this.pausedReason = reason;
		this.nextRunAt = null;
	}

	/** 실행이 끝난 뒤 결과를 반영한다. 사용자가 실행 중 일시정지/취소했다면 그 상태를 유지한다. */
	public void finishRun(Instant ranAt, String result, Boolean success, Instant nextRunAt, boolean oneShotDone) {
		this.lastRunAt = ranAt;
		this.lastResult = result;
		this.runCount++;
		if (success != null) {
			this.failCount = success ? 0 : this.failCount + 1;
		}
		this.nextRunAt = nextRunAt;
		if (this.status == Status.RUNNING) {
			this.status = (oneShotDone || nextRunAt == null) ? Status.DONE : Status.ACTIVE;
		}
	}

	public void setNextRunAt(Instant nextRunAt) {
		this.nextRunAt = nextRunAt;
	}
}
