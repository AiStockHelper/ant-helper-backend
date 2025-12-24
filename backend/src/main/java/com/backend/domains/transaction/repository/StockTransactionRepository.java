package com.backend.domains.transaction.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.backend.domains.transaction.entity.StockTransaction;

public interface StockTransactionRepository extends JpaRepository<StockTransaction, Long> {
}
