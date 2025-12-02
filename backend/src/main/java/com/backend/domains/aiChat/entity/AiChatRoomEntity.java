package com.backend.domains.aiChat.entity;

import java.time.LocalDateTime;

import com.backend.common.entity.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Table(
	name = "ai_chat_rooms",
	uniqueConstraints = {
		@UniqueConstraint(
			name = "uk_ai_chat_rooms_member",
			columnNames = {"member_id"}
		)
	})
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiChatRoomEntity extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "member_id", nullable = false, unique = true)
	private Long memberId;

	// 채팅 내용 요약본
	@Column(name = "chat_summary", columnDefinition = "TEXT")
	private String chatSummary;

	// 컨텍스트(대화 내용) 초기화 시각, 이를 통해 대화 내용이 초기화된 이후의 메시지만 컨텍스트로 사용
	@Column(name = "context_reset_at")
	private LocalDateTime contextResetAt;

	@Builder
	private AiChatRoomEntity(
		Long memberId,
		String chatSummary,
		LocalDateTime contextResetAt
	) {
		this.memberId = memberId;
		this.chatSummary = chatSummary;
		this.contextResetAt = contextResetAt;
	}

	// 채팅 요약 업데이트
	public void updateChatSummary(String chatSummary) {
		this.chatSummary = chatSummary;
	}

	// 컨텍스트 초기화
	public void resetContext() {
		this.contextResetAt = LocalDateTime.now();
		this.chatSummary = ""; // 요약도 초기화
	}
}