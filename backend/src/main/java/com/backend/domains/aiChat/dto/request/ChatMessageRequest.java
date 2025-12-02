package com.backend.domains.aiChat.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 채팅 메시지 전송 요청 DTO
 */
@Schema(description = "채팅 메시지 전송 요청")
public record ChatMessageRequest(
	@Schema(description = "채팅 메시지 내용", example = "안녕하세요! 삼성전자 2주 사주세요", required = true)
	@NotNull(message = "채팅 메시지 내용은 필수입니다.")
	@NotBlank(message = "채팅 메시지 내용은 공백일 수 없습니다.")
	String textContent
) {
}