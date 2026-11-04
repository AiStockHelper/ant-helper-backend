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

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "chat_message", indexes = @Index(name = "idx_chat_msg_conv", columnList = "conversation_id, id"))
public class ChatMessage {

	public enum Role {USER, ASSISTANT}

	public enum Status {COMPLETE, STREAMING, STOPPED, ERROR}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "conversation_id", nullable = false)
	private Long conversationId;

	@Column(name = "member_id", nullable = false)
	private Long memberId;

	@Enumerated(EnumType.STRING)
	@Column(name = "role", nullable = false, length = 12)
	private Role role;
	@Column(name = "content", columnDefinition = "LONGTEXT")
	private String content;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 12)
	private Status status;
	@Column(name = "tool_calls_json", columnDefinition = "LONGTEXT")
	private String toolCallsJson;
	@Column(name = "cards_json", columnDefinition = "LONGTEXT")
	private String cardsJson;

	@Column(name = "model", length = 80)
	private String model;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	public static ChatMessage user(Long conversationId, Long memberId, String content) {
		ChatMessage m = new ChatMessage();
		m.conversationId = conversationId;
		m.memberId = memberId;
		m.role = Role.USER;
		m.content = content;
		m.status = Status.COMPLETE;
		return m;
	}

	public static ChatMessage assistantPlaceholder(Long conversationId, Long memberId) {
		ChatMessage m = new ChatMessage();
		m.conversationId = conversationId;
		m.memberId = memberId;
		m.role = Role.ASSISTANT;
		m.content = "";
		m.status = Status.STREAMING;
		return m;
	}

	@PrePersist
	void onCreate() {
		createdAt = LocalDateTime.now();
	}

	public void finish(String content, Status status, String toolCallsJson, String cardsJson, String model) {
		this.content = content;
		this.status = status;
		this.toolCallsJson = toolCallsJson;
		this.cardsJson = cardsJson;
		this.model = model;
	}

	public void updateContent(String content) {
		this.content = content;
	}
}
