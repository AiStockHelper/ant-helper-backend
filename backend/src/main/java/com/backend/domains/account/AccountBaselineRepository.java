package com.backend.domains.account;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountBaselineRepository extends JpaRepository<AccountBaseline, Long> {

	Optional<AccountBaseline> findByAccountIdAndCurrency(Long accountId, String currency);

	void deleteByAccountIdAndCurrency(Long accountId, String currency);

	void deleteByAccountId(Long accountId);
}
