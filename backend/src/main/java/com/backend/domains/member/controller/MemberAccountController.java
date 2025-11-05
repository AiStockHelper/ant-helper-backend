package com.backend.domains.member.controller;

import static com.backend.common.exception.ErrorCode.*;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.backend.common.dto.DataResponse;
import com.backend.common.swagger.ApiErrorMapping;
import com.backend.domains.member.dto.request.CreateMemberAccountRequest;
import com.backend.domains.member.dto.response.CreateMemberAccountResponse;
import com.backend.domains.member.service.MemberAccountService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@Tag(name = "MEMBER ACCOUNT API", description = "회원 계정에 대한 API입니다.")
@RestController
@RequestMapping("/api/member-accounts")
@RequiredArgsConstructor
public class MemberAccountController {

	private final MemberAccountService memberAccountService;

	@PostMapping
	@Operation(
		summary = "회원 계좌 생성",
		description = """
			회원의 계좌를 생성합니다.
			- accountType: 계좌 유형 (예: REAL_TRADE, PAPER_TRADE)
			"""
	)
	@ApiErrorMapping({KIS_ACCOUNT_NOT_FOUND, DUPLICATE_MEMBER_ACCOUNT})
	public ResponseEntity<DataResponse<CreateMemberAccountResponse>> createMemberAccount(
		@AuthenticationPrincipal Long memberId,
		CreateMemberAccountRequest request
	) {
		long memberAccountId = memberAccountService.saveMemberAccount(
			memberId,
			request.getAccountType(),
			request.getAppKey(),
			request.getSecretKey(),
			request.getAccountNumber(),
			request.getAccountProductCode()
		);

		return ResponseEntity.ok(DataResponse.from(new CreateMemberAccountResponse(memberAccountId)));
	}
}
