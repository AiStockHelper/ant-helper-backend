package com.backend.infra.ai.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiConfig {

	@Bean
	public ChatClient chatClient(OpenAiChatModel model) {
		return ChatClient.builder(model)
			.defaultSystem("You are a helpful assistant.")
			.build();

	}
}