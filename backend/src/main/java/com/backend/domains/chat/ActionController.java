package com.backend.domains.chat;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.backend.common.dto.DataResponse;
import com.backend.domains.chat.dto.ChatDtos.Action;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@Tag(name = "CHAT ACTION API", description = "실전 주문/예약 확인 카드 승인·거절")
@RestController
@RequestMapping("/api/chat/actions")
@RequiredArgsConstructor
public class ActionController {

	private final ActionService actionService;

	@GetMapping("/{id}")
	public ResponseEntity<DataResponse<Action>> get(@AuthenticationPrincipal Long memberId, @PathVariable Long id) {
		return ResponseEntity.ok(DataResponse.from(actionService.get(memberId, id)));
	}

	@PostMapping("/{id}/approve")
	public ResponseEntity<DataResponse<Action>> approve(@AuthenticationPrincipal Long memberId, @PathVariable Long id) {
		return ResponseEntity.ok(DataResponse.from(actionService.approve(memberId, id)));
	}

	@PostMapping("/{id}/reject")
	public ResponseEntity<DataResponse<Action>> reject(@AuthenticationPrincipal Long memberId, @PathVariable Long id) {
		return ResponseEntity.ok(DataResponse.from(actionService.reject(memberId, id)));
	}
}
