package com.backend.domains.transaction.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.backend.common.dto.DataResponse;
import com.backend.domains.transaction.dto.response.GetTransactionAccountsResponse;
import com.backend.domains.transaction.service.StockTransactionAccountService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@Tag(name = "STOCK TRANSACTION ACCOUNT API", description = "자사가 관리하는  계좌 잔액에 대한 API입니다.")
@RestController
@RequestMapping("/api/stock-transaction-accounts")
@RequiredArgsConstructor
public class StockTransactionAccountController {

	private final StockTransactionAccountService stockTransactionAccountService;

	@PostMapping
	@Operation(
		summary = "당사에 위탁할 계좌 생성",
		description = """
			당사에 위탁할 계좌 및 관리 금액을 생성합니다.
			"""
	)
	public ResponseEntity<DataResponse<Void>> createTransactionAccount(
		@AuthenticationPrincipal Long memberId,
		@Parameter(description = "관리할 금액", required = true) @RequestParam long postedBalance
	) {
		stockTransactionAccountService.createTransactionAccount(memberId, postedBalance);

		return ResponseEntity.ok(DataResponse.ok());
	}

	@GetMapping
	@Operation(
		summary = "당사에 위탁한 계좌 조회",
		description = """
			당사에 위탁한 회사의 계좌를 조회합니다.
			"""
	)
	public ResponseEntity<DataResponse<List<GetTransactionAccountsResponse>>> getTransactionAccounts(
		@AuthenticationPrincipal Long memberId
	) {
		List<GetTransactionAccountsResponse> responses = stockTransactionAccountService.getTransactionAccounts(
			memberId);

		return ResponseEntity.ok(DataResponse.from(responses));
	}
}
