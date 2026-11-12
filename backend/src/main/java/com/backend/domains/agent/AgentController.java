package com.backend.domains.agent;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.backend.common.dto.DataResponse;
import com.backend.domains.agent.AgentDtos.AgentTaskDetail;
import com.backend.domains.agent.AgentDtos.AgentTaskDto;
import com.backend.domains.agent.AgentDtos.CreateTaskRequest;
import com.backend.domains.agent.AgentDtos.SkillDto;
import com.backend.domains.agent.AgentDtos.UpdateTaskRequest;
import org.springframework.web.bind.annotation.PatchMapping;
import com.backend.domains.agent.skill.Skill;
import com.backend.domains.agent.skill.SkillRegistry;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@Tag(name = "AGENT API", description = "스킬 목록, 자동 실행 작업 생성/조회/일시정지/재개/중지")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AgentController {

	private final AgentTaskService taskService;
	private final AgentRunService runService;
	private final LedgerSyncService syncService;
	private final SkillRegistry registry;
	private final AgentTradingService trading;
	private final com.backend.domains.account.AccountService accountService;
	private final com.backend.domains.auth.otp.StepUpService stepUp;
	private final com.backend.common.security.audit.SecurityAudit audit;

	// ─────────────────────────── 스킬 ───────────────────────────

	@GetMapping("/skills")
	public ResponseEntity<DataResponse<List<SkillDto>>> skills() {
		List<SkillDto> list = registry.all().stream().map(AgentController::skillDto).toList();
		return ResponseEntity.ok(DataResponse.from(list));
	}

	private static SkillDto skillDto(Skill s) {
		return new SkillDto(s.id(), s.name(), s.summary(), s.detail(), s.category(), s.trades(), s.params());
	}

	// ─────────────────────────── 자동 실행 작업 ───────────────────────────

	@GetMapping("/agent/tasks")
	public ResponseEntity<DataResponse<List<AgentTaskDto>>> list(
		@AuthenticationPrincipal Long memberId,
		@RequestParam(required = false) Long accountId,
		@RequestParam(required = false) String skillId,
		@RequestParam(required = false) String status
	) {
		return ResponseEntity.ok(DataResponse.from(taskService.list(memberId, accountId, skillId, status)));
	}

	@PostMapping("/agent/tasks")
	public ResponseEntity<DataResponse<AgentTaskDto>> create(
		@AuthenticationPrincipal Long memberId,
		@RequestBody CreateTaskRequest request
	) {
		return ResponseEntity.ok(DataResponse.from(taskService.createFromRequest(memberId, request)));
	}

	/**
	 * 걸어 둔 지시(정리본)·제목을 고친다. mode=REPLACE(기본)는 통째로 바꾸고, APPEND 는 기존 정리본 뒤에 추가 지시로 덧붙인다.
	 * 실전 계좌에서 자율 주문하는 작업은 이메일 인증번호를 한 번 더 확인한다. 이전 내용은 이력으로 남는다.
	 */
	@PatchMapping("/agent/tasks/{id}")
	public ResponseEntity<DataResponse<AgentTaskDto>> update(
		@AuthenticationPrincipal Long memberId,
		@PathVariable Long id,
		@RequestBody UpdateTaskRequest request,
		jakarta.servlet.http.HttpServletRequest http
	) {
		AgentTask t = taskService.requireEditable(memberId, id, request);
		var account = accountService.requireOwned(memberId, t.getAccountId());
		boolean realAutonomous = t.isAutonomous() && !account.isPaper();
		if (realAutonomous) {
			stepUp.require(memberId);
		}
		AgentTaskDto dto = taskService.applyEdit(memberId, id, request, realAutonomous);
		audit.record(com.backend.common.security.audit.SecurityAudit.AGENT_EDITED, memberId, null, http.getRemoteAddr(),
			"task=" + id + (request.changesSettings() ? " settings" : ""));
		return ResponseEntity.ok(DataResponse.from(dto));
	}
	@GetMapping("/agent/tasks/{id}")
	public ResponseEntity<DataResponse<AgentTaskDetail>> detail(@AuthenticationPrincipal Long memberId, @PathVariable Long id) {
		return ResponseEntity.ok(DataResponse.from(taskService.detail(memberId, id)));
	}

	/** 한국투자증권 앱 등 앱 밖에서 직접 매매한 것을 내 모든 자동 실행 장부에 지금 반영한다. */
	@PostMapping("/agent/sync")
	public ResponseEntity<DataResponse<LedgerSyncService.Result>> syncAll(@AuthenticationPrincipal Long memberId) {
		return ResponseEntity.ok(DataResponse.from(syncService.syncMember(memberId)));
	}

	@PostMapping("/agent/tasks/{id}/sync")
	public ResponseEntity<DataResponse<LedgerSyncService.Result>> syncOne(@AuthenticationPrincipal Long memberId, @PathVariable Long id) {
		return ResponseEntity.ok(DataResponse.from(syncService.syncTask(memberId, id)));
	}

	@PostMapping("/agent/tasks/{id}/pause")
	public ResponseEntity<DataResponse<AgentTaskDto>> pause(@AuthenticationPrincipal Long memberId, @PathVariable Long id) {
		return ResponseEntity.ok(DataResponse.from(taskService.pause(memberId, id)));
	}

	@PostMapping("/agent/tasks/{id}/resume")
	public ResponseEntity<DataResponse<AgentTaskDto>> resume(@AuthenticationPrincipal Long memberId, @PathVariable Long id) {
		return ResponseEntity.ok(DataResponse.from(taskService.resume(memberId, id)));
	}

	@PostMapping("/agent/tasks/{id}/run")
	public ResponseEntity<DataResponse<Void>> runNow(@AuthenticationPrincipal Long memberId, @PathVariable Long id) {
		runService.runNow(memberId, id);
		return ResponseEntity.ok(DataResponse.ok());
	}

	/** 이 작업이 매수한 종목을 모두 시장가로 정리한다(장이 열려 있을 때만 가능). 작업은 그대로 둔다. */
	@PostMapping("/agent/tasks/{id}/liquidate")
	public ResponseEntity<DataResponse<Map<String, Object>>> liquidate(@AuthenticationPrincipal Long memberId, @PathVariable Long id) {
		AgentTask t = taskService.require(memberId, id);
		var account = accountService.requireOwned(memberId, t.getAccountId());
		if (!account.isPaper()) {
			stepUp.require(memberId);
		}
		List<String> lines = new java.util.ArrayList<>();
		var outcomes = trading.liquidate(t, account, lines);
		return ResponseEntity.ok(DataResponse.from(Map.of("orders", outcomes.size(), "lines", lines)));
	}

	@DeleteMapping("/agent/tasks/{id}")
	public ResponseEntity<DataResponse<AgentTaskDto>> cancel(@AuthenticationPrincipal Long memberId, @PathVariable Long id) {
		return ResponseEntity.ok(DataResponse.from(taskService.cancel(memberId, id)));
	}

	/** 비상 정지: 내 모든 자동 실행을 일시정지한다. */
	@PostMapping("/agent/pause-all")
	public ResponseEntity<DataResponse<Map<String, Object>>> pauseAll(@AuthenticationPrincipal Long memberId) {
		return ResponseEntity.ok(DataResponse.from(Map.of("paused", taskService.pauseAll(memberId))));
	}
}
