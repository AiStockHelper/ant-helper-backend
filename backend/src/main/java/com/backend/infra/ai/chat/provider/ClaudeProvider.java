package com.backend.infra.ai.chat.provider;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.backend.domains.watchList.WatchListService;
import com.backend.order.domestic.service.DomesticStockService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ClaudeProvider implements AiProvider {

	@Qualifier("claudeChatClient")
	private final ChatClient claudeChatClient;

	private final DomesticStockService domesticStockService;
	private final WatchListService watchListService;

	public String prompt(String system, String user) {
		return claudeChatClient.prompt()
			.system(system)
			.user(user)
			.tools(domesticStockService, watchListService)
			.call()
			.content();
	}
}