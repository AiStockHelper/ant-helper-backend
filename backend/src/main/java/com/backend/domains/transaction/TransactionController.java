package com.backend.domains.transaction;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import com.backend.common.dto.DataResponse;
import com.backend.common.dto.ErrorResponse;
import com.backend.common.util.memberLoader.MemberLoader;
import com.backend.domains.member.domain.Member;
import com.backend.domains.transaction.dto.TransactionDTO.FindTransactionResponse;
import com.backend.domains.transaction.dto.TransactionDTO.SaveTransactionRequest;
import lombok.RequiredArgsConstructor;

@Tag(name = "TRANSACTION API", description = "거래에 대한 API입니다.")
@RestController
@RequestMapping("/api/transactions")
@RequiredArgsConstructor
public class TransactionController {

	private final TransactionService transactionService;
	private final MemberLoader memberLoader;

	@PostMapping
	@Operation(
		summary = "주문 하기",
		description = "AI가 자동으로 매수,매도할 주식에 대해 주문한다.",
		responses = {
			@ApiResponse(
				responseCode = "200",
				description = "성공"
			),
			@ApiResponse(
				responseCode = "401",
				description = "유효하지 않은 액세스 토큰입니다.",
				content = @Content(schema = @Schema(implementation = ErrorResponse.class))
			),
			@ApiResponse(
				responseCode = "409",
				description = "주문 아이템이 중복됩니다.",
				content = @Content(schema = @Schema(implementation = ErrorResponse.class))
			)
		}
	)
	public ResponseEntity<DataResponse<Void>> saveTransaction(@RequestBody @Valid SaveTransactionRequest request) {
		Member member = memberLoader.getMember();

		transactionService.saveTransaction(member, request);

		return ResponseEntity.ok(DataResponse.ok());
	}

	@GetMapping
	@Operation(
		summary = "주문 조회 하기",
		description = "회원이 작성한 주문 목록을 조회한다.",
		responses = {
			@ApiResponse(
				responseCode = "200",
				description = "성공"
			),
			@ApiResponse(
				responseCode = "401",
				description = "유효하지 않은 액세스 토큰입니다.",
				content = @Content(schema = @Schema(implementation = ErrorResponse.class))
			)
		}
	)
	public ResponseEntity<DataResponse<FindTransactionResponse>> findTransaction() {
		Member member = memberLoader.getMember();

		FindTransactionResponse response = transactionService.findTransaction(member);

		return ResponseEntity.ok(DataResponse.from(response));
	}

	@PutMapping
	@Operation(
		summary = "주문 변경 하기",
		description = "주식 주문을 변경한다.",
		responses = {
			@ApiResponse(
				responseCode = "200",
				description = "성공"
			),
			@ApiResponse(
				responseCode = "401",
				description = "유효하지 않은 액세스 토큰입니다.",
				content = @Content(schema = @Schema(implementation = ErrorResponse.class))
			),
			@ApiResponse(
				responseCode = "409",
				description = "주문 아이템이 중복됩니다.",
				content = @Content(schema = @Schema(implementation = ErrorResponse.class))
			)
		}
	)
	public ResponseEntity<DataResponse<Void>> updateTransaction(@RequestBody @Valid SaveTransactionRequest request) {
		Member member = memberLoader.getMember();

		transactionService.updateTransaction(member, request);

		return ResponseEntity.ok(DataResponse.ok());
	}

	@DeleteMapping("/all")
	@Operation(
		summary = "주문 전체 삭제하기 하기",
		description = "회원이 작성한 주문 목록을 삭제한다.",
		responses = {
			@ApiResponse(
				responseCode = "200",
				description = "성공"
			),
			@ApiResponse(
				responseCode = "401",
				description = "유효하지 않은 액세스 토큰입니다.",
				content = @Content(schema = @Schema(implementation = ErrorResponse.class))
			)
		}
	)
	public ResponseEntity<DataResponse<Void>> deleteTransaction() {
		Member member = memberLoader.getMember();

		transactionService.deleteTransaction(member);

		return ResponseEntity.ok(DataResponse.ok());
	}
}
