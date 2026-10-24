package com.backend.domains.schedule;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.backend.common.dto.DataResponse;
import com.backend.domains.chat.dto.ChatDtos.ScheduledTaskDto;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@Tag(name = "SCHEDULED TASK API", description = "채팅으로 만든 예약 주문 조회/취소")
@RestController
@RequestMapping("/api/scheduled-tasks")
@RequiredArgsConstructor
public class ScheduledTaskController {

	private final ScheduleService scheduleService;

	@GetMapping
	public ResponseEntity<DataResponse<List<ScheduledTaskDto>>> list(
		@AuthenticationPrincipal Long memberId,
		@RequestParam(required = false) Long accountId,
		@RequestParam(required = false) String status
	) {
		return ResponseEntity.ok(DataResponse.from(scheduleService.list(memberId, accountId, status)));
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<DataResponse<ScheduledTaskDto>> cancel(@AuthenticationPrincipal Long memberId, @PathVariable Long id) {
		return ResponseEntity.ok(DataResponse.from(scheduleService.cancel(memberId, id)));
	}
}
