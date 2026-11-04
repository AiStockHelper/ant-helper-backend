package com.backend.domains.transaction.dto.response;

// AI가 관리하는 계좌 목록 조회(관리 금액)
public record GetTransactionAccountsResponse(
	long accountId,
	long postedBalance,
	long ledgerVersion
) {
}
