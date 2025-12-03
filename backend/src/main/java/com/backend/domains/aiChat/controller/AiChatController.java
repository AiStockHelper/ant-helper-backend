package com.backend.domains.aiChat.controller;

import static com.backend.common.exception.ErrorCode.*;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.backend.common.dto.DataResponse;
import com.backend.common.dto.PageResponse;
import com.backend.common.swagger.ApiErrorMapping;
import com.backend.domains.aiChat.dto.response.ChatContextResetResponse;
import com.backend.domains.aiChat.dto.request.ChatMessageRequest;
import com.backend.domains.aiChat.dto.response.ChatMessageResponse;
import com.backend.domains.aiChat.dto.response.GetChatRoomResponse;
import com.backend.domains.aiChat.service.AiChatMessageService;
import com.backend.domains.aiChat.service.AiChatRoomService;
import com.backend.domains.aiChat.service.AiResponseSseService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Tag(name = "AI 채팅 API", description = "사용자와 AI 간의 채팅 기능 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/ai-chat")
@Validated
@Slf4j
public class AiChatController {

	private final AiChatRoomService aiChatRoomService;
	private final AiChatMessageService aiChatMessageService;
	private final AiResponseSseService aiResponseSseService;

	private static final long SSE_TIMEOUT = 5 * 60 * 1000L; // 5 minutes

	@GetMapping("/room")
	@Operation(
		summary = "채팅방 정보 조회",
		description = """
			채팅방 정보를 조회합니다.
			"""
	)
	@ApiErrorMapping({
		AI_CHAT_ROOM_NOT_FOUND
	})
	public ResponseEntity<DataResponse<GetChatRoomResponse>> getChatRoom(
		@AuthenticationPrincipal Long memberId
	) {
		GetChatRoomResponse response = aiChatRoomService.getChatRoom(memberId);
		return ResponseEntity.ok(DataResponse.from(response));
	}

	@PostMapping("/room")
	@Operation(
		summary = "채팅방 생성",
		description = """
			사용자의 채팅방을 생성합니다.
			채팅방은 한번만 생성할 수 있습니다.
			"""
	)
	@ApiErrorMapping({
		AI_CHAT_ROOM_ALREADY_EXISTS
	})
	public ResponseEntity<DataResponse<GetChatRoomResponse>> createChatRoom(
		@AuthenticationPrincipal Long memberId
	) {
		GetChatRoomResponse response = aiChatRoomService.createChatRoom(memberId);
		return ResponseEntity.ok(DataResponse.from(response));
	}

	@PostMapping("/messages")
	@Operation(
		summary = "채팅 메시지 전송",
		description = """
			AI에게 채팅 메시지를 전송합니다.
			"""
	)
	@ApiErrorMapping({
		AI_CHAT_ROOM_NOT_FOUND,
		INVALID_PARAMETER,
		AI_CHAT_MESSAGE_ORDER_NOT_FOUND
	})
	public ResponseEntity<DataResponse<ChatMessageResponse>> sendUserMessage(
		@AuthenticationPrincipal Long memberId,
		@RequestParam Long memberAccountId,
		@Valid @RequestBody ChatMessageRequest request
	) {
		ChatMessageResponse response = aiChatMessageService.sendUserMessage(memberId, memberAccountId, request);

		return ResponseEntity.ok(DataResponse.from(response));
	}

	@PostMapping("/messages/{messageId}/cancel")
	@Operation(
		summary = "AI 응답 취소",
		description = "진행 중인 AI 응답을 취소합니다. 이미 완료된 응답은 취소할 수 없습니다."
	)
	@ApiErrorMapping({
		AI_CHAT_MESSAGE_NOT_FOUND,
		AI_CHAT_CANNOT_CANCEL
	})
	public ResponseEntity<DataResponse<ChatMessageResponse>> cancelAiResponse(
		@AuthenticationPrincipal Long memberId,
		@Parameter(description = "메시지 ID") @PathVariable @Positive(message = "메시지 ID는 양수여야 합니다.") Long messageId
	) {
		ChatMessageResponse response = aiChatMessageService.cancelAiRequest(memberId, messageId);

		return ResponseEntity.ok(DataResponse.from(response));
	}

	@GetMapping("/messages")
	@Operation(
		summary = "채팅 메시지 목록 조회",
		description = """
			채팅 메시지 목록을 페이지네이션으로 조회합니다.
			page를 -1로 조회하면, 가장 마지막 페이지를 조회합니다.
			senderType을 통해 유저요청(USER)과 AI응답(AI)을 구분할 수 있습니다.
			"""
	)
	@ApiErrorMapping({
		INVALID_PARAMETER
	})
	public ResponseEntity<DataResponse<PageResponse<ChatMessageResponse>>> getChatMessages(
		@AuthenticationPrincipal Long memberId,
		@Parameter(description = "페이지 번호 (0부터 시작)") @RequestParam(defaultValue = "0") @Min(-1) int page,
		@Parameter(description = "페이지 크기 (최대 50)") @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size
	) {
		PageResponse<ChatMessageResponse> responses = aiChatMessageService.getMessages(memberId, page, size);

		return ResponseEntity.ok(DataResponse.from(responses));
	}

	@PostMapping("/context/reset")
	@Operation(
		summary = "채팅 컨텍스트 초기화",
		description = "채팅 컨텍스트를 초기화합니다. 기존 채팅 내역은 유지되지만 AI가 참조하지 않습니다."
	)
	@ApiErrorMapping({
		AI_CHAT_ROOM_NOT_FOUND
	})
	public ResponseEntity<DataResponse<ChatContextResetResponse>> resetContext(
		@AuthenticationPrincipal Long memberId
	) {

		ChatContextResetResponse response = aiChatRoomService.resetContext(memberId);

		return ResponseEntity.ok(DataResponse.from(response));
	}

	// AI 이미지 생성 상태 실시간 구독 (SSE)
	@GetMapping("/sse/{requestId}")
	@Operation(
		summary = "AI 채팅 실시간 구독 (SSE)",
		description = """
			AI 생성 요청 후, 해당 요청 ID로 SSE 구독을 시작해야 실시간으로 채팅을 받을 수 있습니다.
			서버는 이미지 생성 완료 시 SSE를 통해 이미지를 전송하고 서버연결을 끊습니다.
			
			SSE응답 형식은 Message 목록 조회 내용 형식과 유사합니다.
			{
			  "messageId": 1,
			  "messageOrder": 1,
			  "senderType": "AI", // AI 응답
			  "textContent": "안녕하세요! 생성된 메시지입니다.",
			  "requestId": "req-1234567890",
			  "createdAt": "2025-09-24T13:45:00",
			  "status": "RESPONSE" // 응답이므로 RESPONSE
			}
			
			"""
	)
	@ApiErrorMapping({
		INVALID_PARAMETER,
	})
	public SseEmitter subscribe(
		@AuthenticationPrincipal Long memberId,
		@PathVariable @NotBlank(message = "요청 ID는 필수입니다.") String requestId
	) {
		// SSE 연결 생성 및 Emitter 등록, 타임아웃 5분
		SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
		aiResponseSseService.addEmitter(memberId, requestId, emitter);

		// 기타 장애가 나면 연결 해제
		emitter.onCompletion(() -> aiResponseSseService.removeEmitter(requestId));
		emitter.onTimeout(() -> aiResponseSseService.removeEmitter(requestId));
		emitter.onError((e) -> aiResponseSseService.removeEmitter(requestId));

		return emitter;
	}
}