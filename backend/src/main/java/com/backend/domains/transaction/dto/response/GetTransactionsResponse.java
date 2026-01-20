package com.backend.domains.transaction.dto.response;

import com.backend.common.dto.PageResponse;

public record GetTransactionsResponse(
	long coin,
	PageResponse<GetTransactionEntityResponse> transactions
) {
}
