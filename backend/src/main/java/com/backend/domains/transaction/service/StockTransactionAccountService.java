package com.backend.domains.transaction.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backend.domains.transaction.dto.response.GetTransactionAccountsResponse;
import com.backend.domains.transaction.entity.StockTransactionAccount;
import com.backend.domains.transaction.repository.StockTransactionAccountRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class StockTransactionAccountService {

	private final StockTransactionAccountRepository stockTransactionAccountRepository;

	/**
	 * 관리 계좌 생성
	 */
	@Transactional
	public void createTransactionAccount(
		final long memberId,
		final long postedBalance
	) {
		StockTransactionAccount stockTransactionAccount = StockTransactionAccount.builder()
			.memberId(memberId)
			.postedBalance(postedBalance)
			.build();

		stockTransactionAccountRepository.save(stockTransactionAccount);
	}

	/**
	 * 관리 계좌 조회
	 */
	public List<GetTransactionAccountsResponse> getTransactionAccounts(
		final long memberId
	) {
		List<StockTransactionAccount> stockTransactionAccounts = stockTransactionAccountRepository.findAllByMemberId(memberId);

		return stockTransactionAccounts.stream()
			.map(account -> new GetTransactionAccountsResponse(
				account.getId(),
				account.getPostedBalance(),
				account.getLedgerVersion()
			))
			.toList();
	}
}
