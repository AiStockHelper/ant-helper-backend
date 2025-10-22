package com.backend.domains.transaction;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.backend.domains.member.enums.AutoTradeState;
import com.backend.domains.member.domain.Member;
import com.backend.domains.transaction.domain.Transaction;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {
	Optional<Transaction> findByMember(Member member);

	boolean existsByMember(Member member);

	List<Transaction> findAllByMember_AutoTradeState(AutoTradeState autoTradeState);

	void deleteAllByMember(Member member);
}
