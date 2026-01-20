package com.backend.domains.transaction.repository;

import java.time.LocalDateTime;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.backend.domains.transaction.entity.StockTransaction;

public interface StockTransactionRepository extends JpaRepository<StockTransaction, Long> {

	@Query("""
		    SELECT e FROM StockTransaction e
		    WHERE e.accountId = :accountId
		      AND e.effectiveAt <= :timestampA
		      AND (e.discardedAt IS NULL OR e.discardedAt >= :timestampA)
		      AND e.accountVersion <= :version
		""")
	Page<StockTransaction> findSnapshot(
		@Param("accountId") Long accountId,
		@Param("timestampA") LocalDateTime timestampA,
		@Param("version") Long version,
		Pageable pageable
	);
}
