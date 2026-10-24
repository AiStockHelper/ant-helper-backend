package com.backend.domains.schedule;

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
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "scheduled_task", indexes = {
	@Index(name = "idx_sched_status_run", columnList = "status, run_at"),
	@Index(name = "idx_sched_member", columnList = "member_id")
})
public class ScheduledTask {

	public enum Kind {ORDER, RECOMMEND_BUY}

	public enum Status {SCHEDULED, RUNNING, DONE, FAILED, CANCELED}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "member_id", nullable = false)
	private Long memberId;

	@Column(name = "account_id", nullable = false)
	private Long accountId;

	@Column(name = "conversation_id")
	private Long conversationId;

	@Enumerated(EnumType.STRING)
	@Column(name = "kind", nullable = false, length = 20)
	private Kind kind;

	@Column(name = "title", nullable = false, length = 200)
	private String title;

	@Column(name = "run_at", nullable = false)
	private Instant runAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 12)
	private Status status;
	@Column(name = "params_json", columnDefinition = "LONGTEXT")
	private String paramsJson;
	@Column(name = "result_message", columnDefinition = "LONGTEXT")
	private String resultMessage;

	@Column(name = "executed_at")
	private Instant executedAt;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	public ScheduledTask(Long memberId, Long accountId, Long conversationId, Kind kind, String title, Instant runAt,
		String paramsJson) {
		this.memberId = memberId;
		this.accountId = accountId;
		this.conversationId = conversationId;
		this.kind = kind;
		this.title = title;
		this.runAt = runAt;
		this.paramsJson = paramsJson;
		this.status = Status.SCHEDULED;
	}

	@PrePersist
	void onCreate() {
		createdAt = LocalDateTime.now();
	}

	public void finish(Status status, String message) {
		this.status = status;
		this.resultMessage = message;
		this.executedAt = Instant.now();
	}

	public void cancel() {
		this.status = Status.CANCELED;
	}
}
