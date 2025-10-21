package com.backend.common.scheduling;

import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import com.backend.domains.autoTrade.AutoTradeService;
import com.backend.domains.stock.service.StockService;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SchedulingService {

	private final AutoTradeService autoTradeService;
	private final StockService stockService;

	@Async
	@Scheduled(cron = "0 0 11 * * MON-FRI")
	public void doAutoTrade() {
		autoTradeService.executeAutoTradeForAllMembers();
	}

	@Async
	@Scheduled(cron = "0 0 20 * * MON-SAT")
	public void updateSuggestedKeywords() {
		stockService.updateDomesticStocks();
	}
}
