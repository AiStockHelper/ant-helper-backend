package com.backend.domains.aiChat.dto.response;

import java.time.LocalDateTime;

import com.backend.domains.aiChat.entity.AiChatMessageEntity;
import com.backend.domains.aiChat.enums.AiChatStatus;
import com.backend.domains.aiChat.enums.SenderType;

/**
 * 채팅 메시지 응답 DTO
 */
public record ChatMessageResponse(
	Long messageId,
	Long messageOrder,
	SenderType senderType,
	String textContent,
	String requestId, // AI 요청 ID (SSE 연결 시 사용)
	LocalDateTime createdAt,
	AiChatStatus status // 이미지 상태
) {
	public static ChatMessageResponse createResponse(AiChatMessageEntity entity) {
		return new ChatMessageResponse(
			entity.getId(),
			entity.getMessageOrder(),
			entity.getSenderType(),
			entity.getTextContent(),
			entity.getRequestId(),
			entity.getCreatedAt(),
			entity.getStatus()
		);
	}

	// 채팅 생성 실패 시 응답용
	public static ChatMessageResponse createErrorResponse(AiChatMessageEntity entity, String errorMessage) {
		return new ChatMessageResponse(
			entity.getId(),
			entity.getMessageOrder(),
			SenderType.AI,
			errorMessage,
			entity.getRequestId(),
			entity.getCreatedAt(),
			AiChatStatus.RESPONSE_FAILED
		);
	}
}