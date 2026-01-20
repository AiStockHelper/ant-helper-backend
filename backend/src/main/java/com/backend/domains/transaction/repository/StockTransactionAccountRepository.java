package com.backend.domains.transaction.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.w3c.dom.stylesheets.LinkStyle;

import com.backend.domains.transaction.entity.StockTransactionAccount;

public interface StockTransactionAccountRepository extends JpaRepository<StockTransactionAccount, Long> {

	Optional<StockTransactionAccount> findByMemberId(Long memberId);

	Optional<StockTransactionAccount> findByIdAndMemberId(Long accountId, Long memberId);

	List<StockTransactionAccount> findAllByMemberId(Long memberId);
}
