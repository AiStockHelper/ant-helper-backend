package com.backend.domains.transaction.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.backend.common.dto.DataResponse;
import com.backend.domains.transaction.dto.response.GetTransactionAccountsResponse;
import com.backend.domains.transaction.dto.response.GetTransactionsResponse;
import com.backend.domains.transaction.service.StockTransactionService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

@Tag(name = "STOCK TRANSACTION API", description = "주식 거래내역에 대한 API입니다.")
@RestController
@RequestMapping("/api/stock-transactions")
@RequiredArgsConstructor
public class StockTransactionController {

	private final StockTransactionService stockTransactionService;

	@GetMapping
	@Operation(
		summary = "거래내역 조회",
		description = """
			거래내역을 조회합니다.
			"""
	)
	public ResponseEntity<DataResponse<GetTransactionsResponse>> getTransactionAccounts(
		@AuthenticationPrincipal Long memberId,
		@Parameter(description = "당사에 맞긴 계좌 ID") @RequestParam long stockTransactionAccountId,
		@Parameter(description = "페이지 번호 (0부터 시작)") @RequestParam(defaultValue = "0") @Min(-1) int page,
		@Parameter(description = "페이지 크기 (최대 50)") @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size
	) {
		GetTransactionsResponse response = stockTransactionService.getTransactions(
			memberId,
			stockTransactionAccountId,
			page,
			size
		);

		return ResponseEntity.ok(DataResponse.from(response));
	}
}
