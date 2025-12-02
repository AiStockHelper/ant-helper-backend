package com.backend.domains.aiChat.dto.response;

import java.time.LocalDateTime;

import com.backend.domains.aiChat.entity.AiChatRoomEntity;

/**
 * 채팅방 응답 DTO
 */
public record GetChatRoomResponse(
	Long roomId,
	Long memberId,
	String chatSummary,
	LocalDateTime contextResetAt,
	LocalDateTime createdAt
) {
	public static GetChatRoomResponse from(AiChatRoomEntity entity) {
		return new GetChatRoomResponse(
			entity.getId(),
			entity.getMemberId(),
			entity.getChatSummary(),
			entity.getContextResetAt(),
			entity.getCreatedAt()
		);
	}
}