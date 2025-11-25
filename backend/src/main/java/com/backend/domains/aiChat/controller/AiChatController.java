package com.backend.domains.aiChat.controller;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.backend.domains.watchList.WatchListService;
import com.backend.order.domestic.service.DomesticStockService;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@Tag(name = "AI CHAT API", description = "AI 챗봇과의 대화를 위한 API입니다.")
@RestController
@RequiredArgsConstructor
public class AiChatController {

	private final ChatClient chatClient;
	private final DomesticStockService domesticStockService;
	private final WatchListService watchListService;

	@GetMapping("/ask")
	public String ask(
		@AuthenticationPrincipal Long memberId,
		@RequestParam Long memberAccountId,
		@RequestParam String prompt
	) {
		return chatClient.prompt()
			.user(prompt + "memberAccountId:" + memberAccountId + ", memberId:" + memberId)
			.tools(domesticStockService, watchListService)
			.call()
			.content();
	}
}
