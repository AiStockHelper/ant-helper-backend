package com.backend.order.domestic.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.backend.order.domestic.domain.DomesticStock;

public interface DomesticStockRepository extends JpaRepository<DomesticStock, String> {

	// 이름을 포함하는 종목 조회
	List<DomesticStock> findByNameContaining(String name);
}
