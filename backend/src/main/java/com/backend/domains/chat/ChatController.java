package com.backend.domains.chat;

import java.util.List;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.backend.common.dto.DataResponse;
import com.backend.common.dto.PageResponse;
import com.backend.domains.chat.dto.ChatDtos.Conversation;
import com.backend.domains.chat.dto.ChatDtos.ConversationDetail;
import com.backend.domains.chat.dto.ChatDtos.CreateConversationRequest;
import com.backend.domains.chat.dto.ChatDtos.SendMessageRequest;
import com.backend.domains.chat.dto.ChatDtos.UpdateConversationRequest;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Tag(name = "CHAT API", description = "AI 주식 어시스턴트 대화 (SSE 스트리밍)")
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

	private static final List<String> SUGGESTIONS = List.of(
		"삼성전자 지금 얼마야?",
		"엔비디아 내일 오를까?",
		"오늘 추천 종목 5개 알려줘",
		"내 계좌 잔고랑 보유 종목 보여줘",
		"내일 오전 9시에 추천 종목 200만원어치 사줘",
		"애플 주식 10주 사줘"
	);

	private final ChatService chatService;
	private final ChatStreamService streamService;

	@GetMapping("/conversations")
	public ResponseEntity<DataResponse<PageResponse<Conversation>>> list(
		@AuthenticationPrincipal Long memberId,
		@RequestParam(required = false) String q,
		@RequestParam(defaultValue = "0") int page,
		@RequestParam(defaultValue = "30") int size
	) {
		return ResponseEntity.ok(DataResponse.from(chatService.list(memberId, q, page, size)));
	}

	@PostMapping("/conversations")
	public ResponseEntity<DataResponse<Conversation>> create(
		@AuthenticationPrincipal Long memberId,
		@RequestBody(required = false) CreateConversationRequest request
	) {
		return ResponseEntity.ok(DataResponse.from(chatService.create(memberId, request == null ? null : request.accountId())));
	}

	@GetMapping("/conversations/{id}")
	public ResponseEntity<DataResponse<ConversationDetail>> detail(@AuthenticationPrincipal Long memberId, @PathVariable Long id) {
		return ResponseEntity.ok(DataResponse.from(chatService.detail(memberId, id)));
	}

	@PatchMapping("/conversations/{id}")
	public ResponseEntity<DataResponse<Conversation>> update(
		@AuthenticationPrincipal Long memberId, @PathVariable Long id,
		@RequestBody @Valid UpdateConversationRequest request
	) {
		return ResponseEntity.ok(DataResponse.from(chatService.update(memberId, id, request)));
	}

	@DeleteMapping("/conversations/{id}")
	public ResponseEntity<DataResponse<Void>> delete(@AuthenticationPrincipal Long memberId, @PathVariable Long id) {
		chatService.delete(memberId, id);
		return ResponseEntity.ok(DataResponse.ok());
	}

	@DeleteMapping("/conversations")
	public ResponseEntity<DataResponse<Void>> deleteAll(@AuthenticationPrincipal Long memberId) {
		chatService.deleteAll(memberId);
		return ResponseEntity.ok(DataResponse.ok());
	}

	@PostMapping(value = "/conversations/{id}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter send(
		@AuthenticationPrincipal Long memberId, @PathVariable Long id,
		@RequestBody @Valid SendMessageRequest request, HttpServletResponse response
	) {
		noBuffering(response);
		return streamService.send(memberId, id, request);
	}

	@PostMapping(value = "/conversations/{id}/regenerate", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter regenerate(@AuthenticationPrincipal Long memberId, @PathVariable Long id, HttpServletResponse response) {
		noBuffering(response);
		return streamService.regenerate(memberId, id);
	}

	@GetMapping("/suggestions")
	public ResponseEntity<DataResponse<List<String>>> suggestions() {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(DataResponse.from(SUGGESTIONS));
	}

	/** 프록시(nginx 등)가 SSE 를 모아서 보내지 않도록 한다. */
	private void noBuffering(HttpServletResponse response) {
		response.setHeader("X-Accel-Buffering", "no");
		response.setHeader("Cache-Control", "no-cache, no-transform");
		response.setCharacterEncoding("UTF-8");
	}
}
