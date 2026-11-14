package com.backend.domains.screening;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.backend.common.dto.DataResponse;
import com.backend.common.exception.ApiException;
import com.backend.common.exception.ErrorCode;
import com.backend.domains.auth.service.SignupPolicy;
import com.backend.domains.member.repository.MemberRepository;
import com.backend.domains.screening.ScreeningService.GroupStatus;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@Tag(name = "SCREENING API", description = "야간 스크리닝 상태 조회 / 수동 실행")
@RestController
@RequestMapping("/api/screening")
@RequiredArgsConstructor
public class ScreeningController {

	private final ScreeningService screening;
	private final SignupPolicy signupPolicy;
	private final MemberRepository members;

	@GetMapping("/status")
	public ResponseEntity<DataResponse<List<GroupStatus>>> status() {
		return ResponseEntity.ok(DataResponse.from(List.of(screening.status(ScreeningService.KRX), screening.status(ScreeningService.US))));
	}

	/** 운영자(가입 허용 목록에 있는 사용자)만 수동으로 돌릴 수 있다. 수십 분 걸리는 작업이라 바로 돌려주고 백그라운드에서 실행한다. */
	@PostMapping("/run")
	public ResponseEntity<DataResponse<Map<String, Object>>> run(@AuthenticationPrincipal Long memberId, @RequestParam String market) {
		String email = members.findById(memberId).map(m -> m.getEmail()).orElse(null);
		if (!signupPolicy.isAllowed(email)) {
			throw ApiException.from(ErrorCode.FORBIDDEN);
		}
		String group = ScreeningService.normalizeGroup(market);
		if (screening.isRunning(group)) {
			throw ApiException.of(ErrorCode.AGENT_STATE_CONFLICT, "이미 실행 중입니다.");
		}
		screening.runAsync(group);
		return ResponseEntity.ok(DataResponse.from(Map.of("market", group, "started", true)));
	}
}
