package com.backend.domains.chat.entity;

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

/** 실전 계좌 주문/예약 같이 사용자의 승인이 필요한 작업. 승인 전에는 아무것도 실행되지 않는다. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "chat_action", indexes = {
	@Index(name = "idx_chat_action_message", columnList = "message_id"),
	@Index(name = "idx_chat_action_member", columnList = "member_id, status")
})
public class ChatAction {

	public enum Kind {ORDER, SCHEDULED_ORDER, SCHEDULED_RECOMMEND_BUY}

	public enum Status {PENDING, APPROVED, EXECUTED, REJECTED, FAILED, EXPIRED}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "member_id", nullable = false)
	private Long memberId;

	@Column(name = "conversation_id")
	private Long conversationId;

	@Column(name = "message_id")
	private Long messageId;

	@Column(name = "account_id", nullable = false)
	private Long accountId;

	@Enumerated(EnumType.STRING)
	@Column(name = "kind", nullable = false, length = 30)
	private Kind kind;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 12)
	private Status status;

	@Column(name = "title", nullable = false, length = 200)
	private String title;

	@Column(name = "summary", length = 1000)
	private String summary;
	@Column(name = "details_json", columnDefinition = "LONGTEXT")
	private String detailsJson;
	@Column(name = "payload_json", columnDefinition = "LONGTEXT")
	private String payloadJson;
	@Column(name = "result_json", columnDefinition = "LONGTEXT")
	private String resultJson;

	@Column(name = "expires_at", nullable = false)
	private LocalDateTime expiresAt;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	public ChatAction(Long memberId, Long conversationId, Long messageId, Long accountId, Kind kind, String title,
		String summary, String detailsJson, String payloadJson, LocalDateTime expiresAt) {
		this.memberId = memberId;
		this.conversationId = conversationId;
		this.messageId = messageId;
		this.accountId = accountId;
		this.kind = kind;
		this.status = Status.PENDING;
		this.title = title;
		this.summary = summary;
		this.detailsJson = detailsJson;
		this.payloadJson = payloadJson;
		this.expiresAt = expiresAt;
	}

	@PrePersist
	void onCreate() {
		createdAt = LocalDateTime.now();
	}

	public boolean isExpired() {
		return LocalDateTime.now().isAfter(expiresAt);
	}

	public void markStatus(Status status, String resultJson) {
		this.status = status;
		if (resultJson != null) {
			this.resultJson = resultJson;
		}
	}
}
