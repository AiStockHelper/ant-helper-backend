package com.backend.domains.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import com.backend.common.util.Json;
import com.backend.domains.trade.TradePlanner;
import com.backend.market.Market;
import com.backend.market.TradeProfit.Realized;
import com.backend.market.TradingService;

class AgentTradingServiceTradeProfitTest {

	private static AgentTrade trade(long id, String side, String symbol, long qty, double price, int minute) {
		AgentTrade t = mock(AgentTrade.class);
		when(t.getId()).thenReturn(id);
		when(t.getSide()).thenReturn(side);
		when(t.getMarket()).thenReturn(Market.KRX);
		when(t.getSymbol()).thenReturn(symbol);
		when(t.getQuantity()).thenReturn(qty);
		when(t.getPrice()).thenReturn(BigDecimal.valueOf(price));
		when(t.getCreatedAt()).thenReturn(Instant.parse("2026-11-10T00:00:00Z").plusSeconds(minute * 60L));
		return t;
	}

	@Test
	void eachSellOfTheLedgerGetsItsRealizedProfitFromTheEarlierBuys() {
		AgentTradeRepository trades = mock(AgentTradeRepository.class);
		AgentTradingService service = new AgentTradingService(mock(AgentTaskRepository.class), mock(AgentPositionRepository.class), trades,
			mock(TradePlanner.class), mock(TradingService.class), mock(Json.class), mock(TransactionTemplate.class));
		List<AgentTrade> ledger = List.of(
			trade(1, "BUY", "005930", 10, 70000, 0),
			trade(2, "BUY", "000660", 5, 200000, 1),
			trade(3, "SELL", "005930", 4, 74200, 10),            // +6% · +16,800
			trade(4, "SELL", "000660", 5, 190000, 11),           // -5% · -50,000
			trade(5, "SELL", "035420", 1, 200000, 12));          // 이 장부에는 매수 기록이 없다
		when(trades.findByLedgerIdOrderByIdAsc(any())).thenReturn(ledger);
		AgentTask task = new AgentTask(1L, 2L, 3L, "x", "t", "i", "{}", "{}", null, true, null, null, null, 0, AgentTask.CreatedBy.USER);

		Map<Long, Realized> profits = service.tradeProfits(task);

		assertThat(profits).containsOnlyKeys(3L, 4L);
		assertThat(profits.get(3L).costPrice()).isEqualByComparingTo("70000");
		assertThat(profits.get(3L).pnl()).isEqualByComparingTo("16800");
		assertThat(profits.get(3L).pnlRate()).isEqualTo(6.0);
		assertThat(profits.get(4L).pnl()).isEqualByComparingTo("-50000");
		assertThat(profits.get(4L).pnlRate()).isEqualTo(-5.0);
	}
}