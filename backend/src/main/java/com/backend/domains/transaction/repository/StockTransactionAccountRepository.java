package com.backend.domains.transaction.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.backend.domains.transaction.entity.StockTransactionAccount;

public interface StockTransactionAccountRepository extends JpaRepository<StockTransactionAccount, Long> {

	Optional<StockTransactionAccount> findByMemberId(Long memberId);
}
