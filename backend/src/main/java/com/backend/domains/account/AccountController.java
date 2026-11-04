package com.backend.domains.account;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
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

import com.backend.common.dto.DataResponse;
import com.backend.common.dto.PageResponse;
import com.backend.domains.account.AccountDtos.Account;
import com.backend.domains.account.AccountDtos.AccountDetail;
import com.backend.domains.account.AccountDtos.CreateAccountRequest;
import com.backend.domains.account.AccountDtos.UpdateAccountRequest;
import com.backend.market.dto.MarketDtos.AccountSummary;
import com.backend.market.dto.MarketDtos.OrderHistoryItem;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Tag(name = "ACCOUNT API", description = "증권 계좌(모의/실전) 관리와 조회")
@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
public class AccountController {

	private final AccountService accountService;

	@GetMapping
	public ResponseEntity<DataResponse<List<Account>>> list(@AuthenticationPrincipal Long memberId) {
		return ResponseEntity.ok(DataResponse.from(accountService.list(memberId)));
	}

	@PostMapping
	public ResponseEntity<DataResponse<Account>> create(
		@AuthenticationPrincipal Long memberId,
		@RequestBody @Valid CreateAccountRequest request
	) {
		return ResponseEntity.ok(DataResponse.from(accountService.create(memberId, request)));
	}

	@GetMapping("/{id}")
	public ResponseEntity<DataResponse<AccountDetail>> detail(@AuthenticationPrincipal Long memberId, @PathVariable Long id) {
		return ResponseEntity.ok(DataResponse.from(accountService.detail(memberId, id)));
	}

	@PatchMapping("/{id}")
	public ResponseEntity<DataResponse<Account>> update(
		@AuthenticationPrincipal Long memberId,
		@PathVariable Long id,
		@RequestBody @Valid UpdateAccountRequest request
	) {
		return ResponseEntity.ok(DataResponse.from(accountService.update(memberId, id, request)));
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<DataResponse<Void>> delete(@AuthenticationPrincipal Long memberId, @PathVariable Long id) {
		accountService.delete(memberId, id);
		return ResponseEntity.ok(DataResponse.ok());
	}

	@GetMapping("/{id}/summary")
	public ResponseEntity<DataResponse<AccountSummary>> summary(@AuthenticationPrincipal Long memberId, @PathVariable Long id) {
		return ResponseEntity.ok(DataResponse.from(accountService.summary(memberId, id)));
	}

	@GetMapping("/{id}/orders")
	public ResponseEntity<DataResponse<PageResponse<OrderHistoryItem>>> orders(
		@AuthenticationPrincipal Long memberId,
		@PathVariable Long id,
		@RequestParam(defaultValue = "DOMESTIC") String scope,
		@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
		@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
		@RequestParam(defaultValue = "0") int page,
		@RequestParam(defaultValue = "30") int size
	) {
		boolean overseas = "OVERSEAS".equalsIgnoreCase(scope);
		return ResponseEntity.ok(DataResponse.from(accountService.orders(memberId, id, overseas, from, to, page, size)));
	}
}
