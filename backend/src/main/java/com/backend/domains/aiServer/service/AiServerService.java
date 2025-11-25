package com.backend.domains.aiServer.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.backend.domains.aiServer.entity.DomesticStockPredictedPrice;
import com.backend.domains.aiServer.repository.DomesticStockPredictedPriceRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AiServerService {

	private final DomesticStockPredictedPriceRepository dsppRepository;

	// 내일 3% 이상 오를 주식 예측 리스트 반환
	public List<String> getTomorrowRisingStockList() {
		return dsppRepository.findTomorrowRisingStockList().stream()
			.map(DomesticStockPredictedPrice::getProductNumber)
			.toList();
	}
}
