package com.backend.infra.ai.chat.provider;

public interface AiProvider {
	String prompt(String systemPrompt, String userPrompt);
}
