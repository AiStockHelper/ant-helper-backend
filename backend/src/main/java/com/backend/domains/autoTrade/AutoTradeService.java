package com.backend.domains.autoTrade;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backend.domains.aiServer.AiServerService;
import com.backend.domains.aiServer.dto.AiServerDTO.GetOrderListRequest;
import com.backend.domains.aiServer.dto.AiServerDTO.GetOrderListResponse;
import com.backend.domains.aiServer.dto.AiServerDTO.GetOrderListResponse.Element;
import com.backend.domains.broker.service.BrokerService;
import com.backend.domains.member.MemberService;
import com.backend.domains.member.domain.AutoTradeState;
import com.backend.domains.member.domain.Member;
import com.backend.domains.stock.dto.StockDTO.GetStockBalanceResponse;
import com.backend.domains.stock.dto.StockDTO.GetStockBalanceResponse.Output1;
import com.backend.domains.stock.dto.StockDTO.OrderStockRequest;
import com.backend.domains.stock.service.StockService;
import com.backend.domains.transaction.TransactionService;
import com.backend.domains.transaction.domain.Transaction;
import lombok.RequiredArgsConstructor;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AutoTradeService {

	private final AiServerService aiServerService;
	private final StockService stockService;
	private final TransactionService transactionService;
	private final MemberService memberService;
	private final BrokerService brokerService;

	// 자동 거래를 ON한 멤버들에 대해 자동 거래를 수행
	public void executeAutoTradeForAllMembers() {
		transactionService.getAllTransactionsByMemberAutoTrade(AutoTradeState.ON)
			.forEach(this::executeAutoTradeForTransaction);
	}

	private void executeAutoTradeForTransaction(Transaction transaction) {
		Member member = transaction.getMember();

		// accessToken 재발급
		brokerService.getAndSaveAccessToken(member);

		// 자동 거래 상태가 아니면 반환
		if (!memberService.isAutoTradeStateOn(member)) {
			return;
		}

		// 현재 보유 주식량 및 예수금 조회
		GetStockBalanceResponse stockBalanceResponse = stockService.getStockBalance(member, "", "");

		// 거래 가능 금액 계산
		int availableBalance = calculateAvailableBalance(
			Integer.parseInt(stockBalanceResponse.getOutput2().get(0).getDnca_tot_amt()), // 실제 거래 가능 금액
			transaction.getAmount() // 사용자 지정 거래 금액
		);

		// AI 서버에 주문 리스트 조회
		GetOrderListRequest orderListRequest = GetOrderListRequest.from(transaction, availableBalance);
		GetOrderListResponse orderList = aiServerService.getOrderList(orderListRequest);

		// AI 추천 주식과 현재 보유 주식을 비교하여 자동 거래 수행
		performTrades(member, orderList.getStocks(), stockBalanceResponse.getOutput1());
	}

	// 현재 보유 금액과 거래에 명시된 금액을 비교하여 더 큰 금액을 반환
	private int calculateAvailableBalance(int nowAmount, int transactionAmount) {
		return Math.max(nowAmount, transactionAmount);
	}

	// AI 추천 주식과 현재 보유 주식을 비교하여 매수 및 매도 수행
	private void performTrades(Member member, List<Element> aiRecommendStocks, List<Output1> userStocks) {
		Map<String, Output1> userStockMap = convertListToMap(userStocks);

		// 매수 및 매도 로직
		for (Element aiStock : aiRecommendStocks) {
			String productNumber = aiStock.getProductNumber();
			int aiQuantity = aiStock.getQuantity();

			if (userStockMap.containsKey(productNumber)) {
				int userQuantity = Integer.parseInt(userStockMap.get(productNumber).getHldg_qty());
				handleTrade(member, productNumber, aiQuantity, userQuantity);
				userStockMap.remove(productNumber); // 처리된 항목 제거
			} else {
				// aiRecommendStocks 리스트에만 있는 경우 매수
				placeOrder(member, productNumber, aiQuantity, true);
			}
		}

		// 남은 사용자 주식들을 모두 매도
		userStockMap.values().forEach(stock ->
			placeOrder(member, stock.getPdno(), Integer.parseInt(stock.getHldg_qty()), false));
	}

	// List<Output1>을 Map<String, Output1>으로 변환
	private Map<String, Output1> convertListToMap(List<Output1> userStocks) {
		Map<String, Output1> map = new HashMap<>();
		for (Output1 output1 : userStocks) {
			map.put(output1.getPdno(), output1);
		}
		return map;
	}

	// 매수 또는 매도 주문을 처리
	private void handleTrade(Member member, String productNumber, int aiQuantity, int userQuantity) {
		if (aiQuantity > userQuantity) {
			// 매수
			placeOrder(member, productNumber, aiQuantity - userQuantity, true);
		} else if (aiQuantity < userQuantity) {
			// 매도
			placeOrder(member, productNumber, userQuantity - aiQuantity, false);
		}
	}

	// 주식 매수 또는 매도 주문을 실행
	private void placeOrder(Member member, String productNumber, int quantity, boolean isBuy) {
		OrderStockRequest request = OrderStockRequest.builder()
			.PDNO(productNumber)
			.ORD_DVSN("01")
			.ORD_UNPR("0")
			.ORD_QTY(String.valueOf(quantity))
			.build();

		if (isBuy) {
			stockService.orderStock(member, request);
		} else {
			stockService.sellStock(member, request);
		}
	}
}


