package com.backend.domains.aiChat.dto;

import java.time.LocalDateTime;

/**
 * 컨텍스트 초기화 응답 DTO
 */
public record ChatContextResetResponse(
	Long roomId,
	LocalDateTime contextResetAt
) {
}