package com.backend.domains.transaction.service;

import static com.backend.common.exception.ErrorCode.*;

import java.time.LocalDateTime;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.backend.common.dto.PageResponse;
import com.backend.common.exception.ApiException;
import com.backend.domains.transaction.dto.response.GetTransactionEntityResponse;
import com.backend.domains.transaction.dto.response.GetTransactionsResponse;
import com.backend.domains.transaction.entity.StockTransaction;
import com.backend.domains.transaction.entity.StockTransactionAccount;
import com.backend.domains.transaction.repository.StockTransactionAccountRepository;
import com.backend.domains.transaction.repository.StockTransactionRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class StockTransactionService {

	private final StockTransactionAccountRepository stockTransactionAccountRepository;
	private final StockTransactionRepository stockTransactionEntityRepository;

	/**
	 * 거래 내역 조회
	 */
	public GetTransactionsResponse getTransactions(
		final long memberId,
		final long accountId,
		final int page,
		final int size
	) {
		// 현재 시점
		LocalDateTime now = LocalDateTime.now();

		// 계좌 조회
		StockTransactionAccount stockTransactionAccount = stockTransactionAccountRepository
			.findByIdAndMemberId(accountId, memberId)
			.orElseThrow(() -> new ApiException(ACCOUNT_NOT_FOUND_EXCEPTION));

		// 거래 내역 조회 및 응답 생성
		Pageable pageable = PageRequest.of(page, size);
		Page<StockTransaction> stockTransactions = stockTransactionEntityRepository.findSnapshot(
			stockTransactionAccount.getId(),
			now,
			stockTransactionAccount.getLedgerVersion(),
			pageable
		);
		PageResponse<GetTransactionEntityResponse> pageResponse = PageResponse.of(
			stockTransactions.map(GetTransactionEntityResponse::from)
		);

		return new GetTransactionsResponse(
			stockTransactionAccount.getPostedBalance(),
			pageResponse
		);
	}
}
