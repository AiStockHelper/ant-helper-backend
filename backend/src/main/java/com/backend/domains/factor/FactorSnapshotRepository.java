package com.backend.domains.factor;

import java.time.LocalDate;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FactorSnapshotRepository extends JpaRepository<FactorSnapshot, Long> {

	boolean existsByTradeDateAndSymbol(LocalDate tradeDate, String symbol);
}
