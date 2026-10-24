package com.backend.domains.chat.dto;

import java.time.OffsetDateTime;
import java.util.List;

import com.backend.order.kis.enums.AccountType;

import jakarta.validation.constraints.Size;

/** DESIGN.md 3.4 ~ 3.6 의 응답 타입. 필드명은 프론트엔드와의 계약이다. */
public final class ChatDtos {

	private ChatDtos() {
	}

	public record Conversation(
		Long id, String title, boolean pinned, Long accountId, String lastMessagePreview,
		OffsetDateTime createdAt, OffsetDateTime updatedAt
	) {
	}

	public record ConversationDetail(
		Long id, String title, boolean pinned, Long accountId, String lastMessagePreview,
		OffsetDateTime createdAt, OffsetDateTime updatedAt, List<Message> messages
	) {
	}

	public record ToolCall(String id, String name, String label, String status, String summary) {
	}

	public record Card(String type, Object data) {
	}

	public record Detail(String label, String value) {
	}

	public record ActionResult(boolean success, String message, String orderNo) {
	}

	public record Action(
		Long id, String kind, String status, String title, String summary,
		Long accountId, AccountType accountType, String accountLabel,
		List<Detail> details, ActionResult result, OffsetDateTime expiresAt, OffsetDateTime createdAt
	) {
	}

	public record Message(
		Long id, String role, String content, String status,
		List<ToolCall> toolCalls, List<Card> cards, List<Action> actions,
		String model, OffsetDateTime createdAt
	) {
	}

	public record CreateConversationRequest(Long accountId) {
	}

	public record UpdateConversationRequest(
		@Size(min = 1, max = 200, message = "제목은 1~200자여야 합니다.") String title,
		Boolean pinned,
		Long accountId
	) {
	}

	public record SendMessageRequest(
		@Size(max = 8000, message = "메시지는 8000자 이하여야 합니다.") String content,
		Long accountId,
		Long editMessageId
	) {
	}

	public record ScheduledTaskDto(
		Long id, Long accountId, AccountType accountType, String kind, String title, OffsetDateTime runAt,
		String status, java.util.Map<String, Object> params, String resultMessage,
		OffsetDateTime executedAt, OffsetDateTime createdAt
	) {
	}
}
