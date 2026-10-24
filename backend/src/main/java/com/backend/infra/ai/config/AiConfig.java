package com.backend.infra.ai.config;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 시스템 프롬프트와 도구는 요청마다(사용자/계좌/현재 시각에 따라) 달라지므로 ChatClient 에는 기본값을 두지 않는다.
 */
@Configuration
public class AiConfig {

	/** GPT (OpenAI) ChatClient */
	@Bean
	public ChatClient openAiChatClient(OpenAiChatModel gptModel) {
		return ChatClient.builder(gptModel).build();
	}

	/** Claude (Anthropic) ChatClient */
	@Bean
	public ChatClient claudeChatClient(AnthropicChatModel claudeModel) {
		return ChatClient.builder(claudeModel).build();
	}
}
