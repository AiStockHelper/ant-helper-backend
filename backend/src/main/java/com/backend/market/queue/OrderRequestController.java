package com.backend.market.queue;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.backend.common.dto.DataResponse;
import com.backend.common.exception.ApiException;
import com.backend.common.exception.ErrorCode;
import com.backend.common.util.TimeUtil;
import com.backend.domains.member.domain.MemberAccount;
import com.backend.domains.member.repository.MemberAccountRepository;
import com.backend.market.Currency;
import com.backend.market.Market;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@Tag(name = "ORDER QUEUE API", description = "주문 대기열: 재시도 중인 주문과 실패한 주문 조회, 실패한 주문 지우기")
@RestController
@RequestMapping("/api/order-requests")
@RequiredArgsConstructor
public class OrderRequestController {

	public record OrderRequestDto(
		Long id, Long accountId, String accountName, String source, Market market, Currency currency, String symbol,
		String name, String side, long quantity, BigDecimal limitPrice, String status, int attempts, int maxRetries,
		OffsetDateTime nextAttemptAt, String failureKind, String lastError, String orderNo, OffsetDateTime createdAt,
		OffsetDateTime completedAt
	) {
	}

	private final KisOrderRequestRepository requests;
	private final MemberAccountRepository accounts;
	private final OrderQueueService queue;
	private final TransactionTemplate tx;

	/** status: PENDING(재시도 대기·전송 중) / FAILED(실패) / SUCCESS / 생략하면 전체 */
	@GetMapping
	public ResponseEntity<DataResponse<List<OrderRequestDto>>> list(
		@AuthenticationPrincipal Long memberId,
		@RequestParam(required = false) Long accountId,
		@RequestParam(required = false) String status
	) {
		List<KisOrderRequest> rows;
		var statuses = statuses(status);
		if (statuses == null) {
			rows = accountId == null ? requests.findTop100ByMemberIdOrderByIdDesc(memberId)
				: requests.findTop100ByMemberIdAndAccountIdOrderByIdDesc(memberId, accountId);
		} else {
			rows = accountId == null ? requests.findTop100ByMemberIdAndStatusInOrderByIdDesc(memberId, statuses)
				: requests.findTop100ByMemberIdAndAccountIdAndStatusInOrderByIdDesc(memberId, accountId, statuses);
		}
		Map<Long, String> names = new HashMap<>();
		accounts.findAllByMemberId(memberId).forEach(a -> names.put(a.getId(), label(a)));
		return ResponseEntity.ok(DataResponse.from(rows.stream().map(r -> dto(r, names)).toList()));
	}

	/**
	 * 실패한 주문 지우기. 재시도를 기다리는 주문에 쓰면 더 이상 보내지 않도록 취소하고(실패 목록으로 이동),
	 * 이미 끝난 주문(실패·성공)에 쓰면 목록에서 지운다. 지금 전송 중인 주문은 지울 수 없다.
	 */
	@DeleteMapping("/{id}")
	public ResponseEntity<DataResponse<Void>> delete(@AuthenticationPrincipal Long memberId, @PathVariable Long id) {
		KisOrderRequest r = requests.findByIdAndMemberId(id, memberId)
			.orElseThrow(() -> ApiException.from(ErrorCode.RESOURCE_NOT_FOUND));
		switch (r.getStatus()) {
			case SENDING -> throw ApiException.of(ErrorCode.AGENT_STATE_CONFLICT, "지금 전송 중인 주문입니다. 잠시 후 다시 시도해 주세요.");
			case RETRY_WAIT -> {
				MemberAccount account = accounts.findById(r.getAccountId()).orElse(null);
				queue.markCancelledOrMissing(id, account, r, "사용자가 재시도를 취소했습니다.");
			}
			default -> tx.executeWithoutResult(s -> requests.deleteById(id));
		}
		return ResponseEntity.ok(DataResponse.ok());
	}

	@DeleteMapping
	public ResponseEntity<DataResponse<Map<String, Integer>>> deleteFailed(@AuthenticationPrincipal Long memberId,
		@RequestParam(required = false) Long accountId) {
		List<KisOrderRequest> failed = accountId == null
			? requests.findTop100ByMemberIdAndStatusInOrderByIdDesc(memberId, EnumSet.of(KisOrderRequest.Status.FAILED))
			: requests.findTop100ByMemberIdAndAccountIdAndStatusInOrderByIdDesc(memberId, accountId,
				EnumSet.of(KisOrderRequest.Status.FAILED));
		tx.executeWithoutResult(s -> requests.deleteAll(failed));
		return ResponseEntity.ok(DataResponse.from(Map.of("deleted", failed.size())));
	}

	private static java.util.Set<KisOrderRequest.Status> statuses(String status) {
		if (status == null || status.isBlank() || status.equalsIgnoreCase("ALL")) {
			return null;
		}
		return switch (status.toUpperCase()) {
			case "PENDING" -> EnumSet.of(KisOrderRequest.Status.SENDING, KisOrderRequest.Status.RETRY_WAIT);
			case "FAILED" -> EnumSet.of(KisOrderRequest.Status.FAILED);
			case "SUCCESS" -> EnumSet.of(KisOrderRequest.Status.SUCCESS);
			default -> throw ApiException.from(ErrorCode.INVALID_PARAMETER);
		};
	}

	private static String label(MemberAccount a) {
		return a.displayName() + (a.isPaper() ? " (모의)" : " (실전)");
	}

	private static OrderRequestDto dto(KisOrderRequest r, Map<Long, String> names) {
		return new OrderRequestDto(r.getId(), r.getAccountId(), names.getOrDefault(r.getAccountId(), "삭제된 계좌"),
			r.getSourceType().name(), r.getMarket(), r.getMarket().currency(), r.getSymbol(), r.getName(), r.getSide(),
			r.getQuantity(), r.getLimitPrice(), r.getStatus().name(), r.getAttempts(), KisLimits.ORDER_MAX_RETRIES,
			TimeUtil.kst(r.getNextAttemptAt()), r.getFailureKind() == null ? null : r.getFailureKind().name(), r.getLastError(),
			r.getOrderNo(), TimeUtil.kst(r.getCreatedAt()), TimeUtil.kst(r.getCompletedAt()));
	}
}
