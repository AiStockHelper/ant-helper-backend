package com.backend.domains.aiServer;

import java.util.HashMap;

import org.springframework.stereotype.Service;

import com.backend.common.util.webClient.WebClientUtil;
import com.backend.domains.aiServer.dto.AiServerDTO.GetOrderListRequest;
import com.backend.domains.aiServer.dto.AiServerDTO.GetOrderListResponse;
import com.backend.domains.transaction.TransactionService;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AiServerService {

	private final WebClientUtil webClientUtil;
	private final TransactionService transactionService;

	public GetOrderListResponse getOrderList(GetOrderListRequest request) {
		String url = "/api/stocks/evaluation";

		return webClientUtil.postFromAiServer(
			new HashMap<>(),
			url,
			request,
			GetOrderListResponse.class);
	}
}
