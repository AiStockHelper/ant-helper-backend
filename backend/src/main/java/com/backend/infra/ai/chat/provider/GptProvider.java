package com.backend.infra.ai.chat.provider;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.backend.domains.watchList.WatchListService;
import com.backend.order.domestic.service.DomesticStockService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class GptProvider implements AiProvider {

	@Qualifier("openAiChatClient")
	private final ChatClient openAiChatClient;

	private final DomesticStockService domesticStockService;
	private final WatchListService watchListService;

	@Override
	public String prompt(String systemPrompt, String userPrompt) {
		return openAiChatClient.prompt()
			.system(systemPrompt)
			.user(userPrompt)
			.tools(domesticStockService, watchListService)
			.call()
			.content();
	}
}