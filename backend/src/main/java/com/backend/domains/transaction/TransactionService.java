package com.backend.domains.transaction;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backend.common.exception.ApiException;
import com.backend.common.exception.ErrorCode;
import com.backend.domains.member.domain.AutoTradeState;
import com.backend.domains.member.domain.Member;
import com.backend.domains.transaction.domain.Transaction;
import com.backend.domains.transaction.domain.TransactionItem;
import com.backend.domains.transaction.dto.TransactionDTO.FindTransactionResponse;
import com.backend.domains.transaction.dto.TransactionDTO.FindTransactionResponse.Element;
import com.backend.domains.transaction.dto.TransactionDTO.SaveTransactionRequest;
import lombok.RequiredArgsConstructor;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class TransactionService {

	private final TransactionRepository transactionRepository;

	// 거래 저장
	@Transactional
	public void saveTransaction(Member member, SaveTransactionRequest saveTransactionRequest) {
		// 거래 존재 확인
		checkTransactionExistence(member);
		// TransactionItem 중복 확인
		checkProductNumberDuplication(saveTransactionRequest.getTransactionItems());

		Transaction transaction = Transaction.builder()
			.amount(saveTransactionRequest.getAmount())
			.member(member)
			.build();

		// TransactionItem 생성 후 transaction에 넣기
		saveTransactionRequest.getTransactionItems()
			.forEach(element -> TransactionItem.builder()
				.productNumber(element.getProductNumber())
				.name(element.getName())
				.industry(element.getIndustry())
				.transaction(transaction)
				.build());

		transactionRepository.save(transaction);
	}

	// 거래 존재 확인
	private void checkTransactionExistence(Member member) {
		boolean existence = transactionRepository.existsByMember(member);

		if (existence) {
			throw ApiException.from(ErrorCode.TRANSACTION_DUPLICATE);
		}
	}

	// TransactionItem의 productNumber 중복 확인
	private void checkProductNumberDuplication(List<SaveTransactionRequest.Element> transactionItems) {
		int count = (int)transactionItems.stream()
			.map(SaveTransactionRequest.Element::getProductNumber)
			.distinct()
			.count();

		if (count != transactionItems.size()) {
			throw ApiException.from(ErrorCode.PRODUCT_NUMBER_DUPLICATE);
		}
	}

	// 회원 거래 조회
	public FindTransactionResponse findTransaction(Member member) {
		return transactionRepository.findByMember(member)
			.map(transaction -> {
				List<Element> elements = transaction.getTransactionItems().stream()
					.map(Element::from)
					.toList();
				return FindTransactionResponse.of(true, transaction.getAmount(), elements);
			})
			.orElseGet(() -> FindTransactionResponse.of(false, null, null));
	}

	// 멤버의 자동거래 상태에 따른 거래 조회
	public List<Transaction> getAllTransactionsByMemberAutoTrade(AutoTradeState autoTradeState) {
		return transactionRepository.findAllByMember_AutoTradeState(autoTradeState);
	}

	// 거래 수정
	@Transactional(rollbackFor = {ApiException.class})
	public void updateTransaction(Member member, SaveTransactionRequest request) {
		transactionRepository.deleteAllByMember(member);

		saveTransaction(member, request);
	}

	// 거래 삭제
	@Transactional
	public void deleteTransaction(Member member) {
		transactionRepository.findByMember(member)
			.ifPresent(transactionRepository::delete);
	}

}
