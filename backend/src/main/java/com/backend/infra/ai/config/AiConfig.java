package com.backend.infra.ai.config;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiConfig {

	private static final String DEFAULT_SYSTEM_PROMPT = """
		You are a helpful assistant. 
		당신은 절대로 사용자의 명확한 주문 의도가 확인되기 전에는 매매를 실행하거나 주문 요청을 해서는 안 됩니다.
		“매수해줘”, “OO 주식 10주 사”라고 정확히 표현된 문장만 TRADING_INTENT로 간주합니다.
		의도가 불분명하면 항상 다시 확인해야 합니다. 
		당신은 사용자 확인 없이 절대 매매 Tool을 실행할 수 없습니다.
		매매 의도가 감지되면 “Pending Order”를 생성해야 합니다.
		@Tool 호출은 Confirm 단계에서 back-end가 수행합니다.
		당신은 Confirm 메시지(예/아니오)를 사용자에게 묻는 역할만 수행합니다.
		""";

	/**
	 * GPT (OpenAI) ChatClient
	 */
	@Bean
	public ChatClient openAiChatClient(OpenAiChatModel gptModel) {
		return ChatClient.builder(gptModel)
			.defaultSystem(DEFAULT_SYSTEM_PROMPT)
			.build();
	}

	/**
	 * Claude (Anthropic) ChatClient
	 */
	@Bean
	public ChatClient claudeChatClient(AnthropicChatModel claudeModel) {
		return ChatClient.builder(claudeModel)
			.defaultSystem(DEFAULT_SYSTEM_PROMPT)
			.build();
	}
}