package com.backend.domains.aiServer.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.backend.domains.aiServer.entity.DomesticStockPredictedPrice;

public interface DomesticStockPredictedPriceRepository extends JpaRepository<DomesticStockPredictedPriceRepository, Long> {

	@Query(
		"SELECT dsp FROM DomesticStockPredictedPrice dsp WHERE (dsp.predictedPrice - dsp.todayPrice) / dsp.todayPrice >= 3.0"
	)
	List<DomesticStockPredictedPrice> findTomorrowRisingStockList();
}
