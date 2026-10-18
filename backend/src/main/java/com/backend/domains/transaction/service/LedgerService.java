package com.backend.domains.transaction.service;


import static com.backend.common.exception.ErrorCode.*;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backend.common.exception.ApiException;
import com.backend.domains.transaction.entity.StockTransaction;
import com.backend.domains.transaction.entity.StockTransactionAccount;
import com.backend.domains.transaction.enums.StockTransactionDirection;
import com.backend.domains.transaction.enums.StockTransactionStatus;
import com.backend.domains.transaction.repository.StockTransactionRepository;
import com.backend.domains.transaction.repository.StockTransactionAccountRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class LedgerService {

	// 계좌, 거래 관련
	private final StockTransactionAccountRepository stockTransactionAccountRepository;
	private final StockTransactionRepository stockTransactionRepository;
	// private final TransactionEntityRepository entryRepository;
	//
	// // 거래 처리(게좌 변경, 트랜잭션, 엔티티 저장) 후 알림 처리
	// @Transactional
	// public synchronized void transfer(final long fromMemberId, final long toMemberId, final long amount) {
	// 	// 락 걸고 양도 처리
	// 	// 1) 계좌 읽기
	// 	StockTransactionAccount fromAccount = stockTransactionAccountRepository.findByMemberId(fromMemberId)
	// 		.orElseThrow(() -> new ApiException(ACCOUNT_NOT_FOUND_EXCEPTION));
	// 	StockTransactionAccount toAccount = stockTransactionAccountRepository.findByMemberId(toMemberId)
	// 		.orElseThrow(() -> new ApiException(ACCOUNT_NOT_FOUND_EXCEPTION));
	//
	// 	// 2) posted_balance 업데이트
	// 	fromAccount.updateBalance(fromAccount.getPostedBalance() - amount);
	// 	toAccount.updateBalance(toAccount.getPostedBalance() + amount);
	//
	// 	// 3) 버전 캡처, 반드시 Account 업데이트 이후에 호출되어야 함
	// 	long fromVersionBefore = fromAccount.getLedgerVersion();
	// 	long toVersionBefore = toAccount.getLedgerVersion();
	//
	// 	LocalDateTime now = LocalDateTime.now();
	//
	// 	// 4) 트랜잭션 생성 (posted)
	// 	StockTransaction txn = stockTransactionRepository.save(
	// 		StockTransaction.builder()
	// 			.status(StockTransactionStatus.POSTED)
	// 			.effectiveAt(now)
	// 			.build()
	// 	);
	//
	// 	// 5) 엔트리 생성
	// 	entryRepository.save(TransactionEntity.createPostedTransaction(
	// 		fromAccount.getId(), fromVersionBefore, txn.getId(),
	// 		StockTransactionDirection.BUY, amount, now
	// 	));
	// 	entryRepository.save(TransactionEntity.createPostedTransaction(
	// 		toAccount.getId(), toVersionBefore, txn.getId(),
	// 		StockTransactionDirection.SELL, amount, now
	// 	));
	// 	stockTransactionAccountRepository.save(fromAccount);
	// 	stockTransactionAccountRepository.save(toAccount);
	// }
}
