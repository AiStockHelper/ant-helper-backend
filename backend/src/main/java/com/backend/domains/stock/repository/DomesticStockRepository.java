package com.backend.domains.stock.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.backend.domains.stock.domain.DomesticStock;

public interface DomesticStockRepository extends JpaRepository<DomesticStock, Long> {

	@Query("SELECT d FROM DomesticStock d WHERE d.name LIKE %:keyword%")
	List<DomesticStock> findAllByKeyword(@Param("keyword") String keyword, Pageable pageable);

	Optional<DomesticStock> findByProductNumber(String productNumber);
}
