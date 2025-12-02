package com.backend.domains.aiChat.entity;

import com.backend.common.entity.BaseEntity;
import com.backend.domains.aiChat.enums.AiChatStatus;
import com.backend.domains.aiChat.enums.SenderType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 요청과 응답을 모두 포함하는 채팅 메시지 엔티티
 * 텍스트와 이미지(aiRequestId)를 함께 관리
 */
@Entity
@Table(name = "ai_chat_messages",
	indexes = {
		@Index(name = "idx_member_order", columnList = "member_id, message_order"),
		@Index(name = "idx_member_created", columnList = "member_id, created_at")
	})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiChatMessageEntity extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "member_id", nullable = false)
	private Long memberId;

	@Column(name = "ai_chat_room_id", nullable = false)
	private Long aiChatRoomId;

	@Column(name = "message_order", nullable = false)
	private Long messageOrder;

	@Enumerated(EnumType.STRING)
	@Column(name = "sender_type", nullable = false)
	private SenderType senderType;

	@Column(name = "text_content", columnDefinition = "TEXT", nullable = false)
	private String textContent;

	@Column(name = "request_id", nullable = false)
	private String requestId; // ai 요청 아이디, 이 ID를 통해 SSE 연결

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private AiChatStatus status;

	@Builder
	public AiChatMessageEntity(
		Long memberId,
		Long aiChatRoomId,
		Long messageOrder,
		SenderType senderType,
		String textContent,
		String requestId,
		AiChatStatus status
	) {
		this.memberId = memberId;
		this.aiChatRoomId = aiChatRoomId;
		this.messageOrder = messageOrder;
		this.senderType = senderType;
		this.textContent = textContent;
		this.requestId = requestId;
		this.status = status;
	}

	// 요청 상태 업데이트
	public void updateStatus(AiChatStatus status) {
		this.status = status;
	}

	// 텍스트 내용 업데이트
	public void updateTextContent(String textContent) {
		this.textContent = textContent;
	}
}