package com.backend.domains.agent;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 자동 실행 한 번의 기록. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "agent_run", indexes = {
	@Index(name = "idx_agent_run_task", columnList = "task_id, id"),
	@Index(name = "idx_agent_run_member", columnList = "member_id")
})
public class AgentRun {

	public enum Status {DONE, FAILED, SKIPPED}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "task_id", nullable = false)
	private Long taskId;

	@Column(name = "member_id", nullable = false)
	private Long memberId;

	@jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
	@Column(name = "status", nullable = false, length = 10)
	private Status status;

	@Column(name = "manual", nullable = false)
	private boolean manual;

	@Column(name = "started_at", nullable = false)
	private Instant startedAt;

	@Column(name = "finished_at", nullable = false)
	private Instant finishedAt;

	@Column(name = "summary", columnDefinition = "LONGTEXT")
	private String summary;

	/** 실행 중 일어난 일들(문자열 목록 JSON) */
	@Column(name = "events_json", columnDefinition = "LONGTEXT")
	private String eventsJson;

	@Column(name = "model", length = 80)
	private String model;

	public AgentRun(Long taskId, Long memberId, Status status, boolean manual, Instant startedAt, Instant finishedAt,
		String summary, String eventsJson, String model) {
		this.taskId = taskId;
		this.memberId = memberId;
		this.status = status;
		this.manual = manual;
		this.startedAt = startedAt;
		this.finishedAt = finishedAt;
		this.summary = summary;
		this.eventsJson = eventsJson;
		this.model = model;
	}
}
