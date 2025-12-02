package com.backend.domains.aiChat.enums;

public enum AiChatStatus {
	REQUEST_REPLIED, // 요청 상태 및 요청 완료 상태
	REQUEST_PENDING, // AI 요청 대기 상태
	REQUEST_FAILED, // AI 요청 실패 상태
	REQUEST_CANCELLED, // AI 요청 취소 상태
	RESPONSE, // 응답을 의미
	RESPONSE_FAILED // 응답 처리 실패 상태
}