package com.backend.domains.chat.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "chat_conversation", indexes = @Index(name = "idx_chat_conv_member_updated", columnList = "member_id, updated_at"))
public class ChatConversation {

	public static final String DEFAULT_TITLE = "새 대화";

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "member_id", nullable = false)
	private Long memberId;

	@Column(name = "title", nullable = false, length = 200)
	private String title;

	@Column(name = "pinned", nullable = false)
	private boolean pinned;

	@Column(name = "account_id")
	private Long accountId;

	@Column(name = "last_message_preview", length = 200)
	private String lastMessagePreview;

	/** 오래된 대화를 압축한 요약. summarizedUntilId 까지의 메시지를 대체한다. */
	@Column(name = "summary", columnDefinition = "LONGTEXT")
	private String summary;

	@Column(name = "summarized_until_id")
	private Long summarizedUntilId;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;

	public ChatConversation(Long memberId, Long accountId) {
		this.memberId = memberId;
		this.accountId = accountId;
		this.title = DEFAULT_TITLE;
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

	public boolean hasDefaultTitle() {
		return DEFAULT_TITLE.equals(title);
	}

	public void rename(String title) {
		this.title = title.length() > 200 ? title.substring(0, 200) : title;
	}

	public void pin(boolean pinned) {
		this.pinned = pinned;
	}

	public void useAccount(Long accountId) {
		this.accountId = accountId;
	}

	public void updatePreview(String text) {
		if (text == null) {
			this.lastMessagePreview = null;
			return;
		}
		String flat = text.replaceAll("\\s+", " ").trim();
		this.lastMessagePreview = flat.length() > 120 ? flat.substring(0, 120) : flat;
	}

	public void applySummary(String summary, Long summarizedUntilId) {
		this.summary = summary;
		this.summarizedUntilId = summarizedUntilId;
	}

	/** 목록 정렬 갱신용(내용이 안 바뀌어도 updatedAt 을 올린다). */
	public void touch() {
		this.updatedAt = LocalDateTime.now();
	}
}
