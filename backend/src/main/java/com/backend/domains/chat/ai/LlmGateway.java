package com.backend.domains.chat.ai;

import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

/**
 * GPT 를 기본으로, 실패하면 Claude 로 넘어가는 LLM 게이트웨이.
 * GPT 호출 결과는 서킷브레이커(gptApi)에 기록되어 연속 실패하면 일정 시간 Claude 를 먼저 쓴다.
 */
@Slf4j
@Component
public class LlmGateway {

	public enum Provider {GPT, CLAUDE}

	private final ChatClient openAi;
	private final ChatClient claude;
	private final CircuitBreaker breaker;
	private final String gptModel;
	private final String claudeModel;

	public LlmGateway(
		@Qualifier("openAiChatClient") ChatClient openAi,
		@Qualifier("claudeChatClient") ChatClient claude,
		CircuitBreakerRegistry registry,
		@Value("${spring.ai.openai.chat.options.model:gpt-4.1}") String gptModel,
		@Value("${spring.ai.anthropic.chat.options.model:claude-sonnet-4-5-20250929}") String claudeModel
	) {
		this.openAi = openAi;
		this.claude = claude;
		this.breaker = registry.circuitBreaker("gptApi");
		this.gptModel = gptModel;
		this.claudeModel = claudeModel;
		breaker.getEventPublisher()
			.onStateTransition(e -> log.warn("GPT 서킷브레이커 상태 전환: {}", e.getStateTransition()));
	}

	public String modelName(Provider p) {
		return p == Provider.GPT ? gptModel : claudeModel;
	}

	/** 서킷이 열려 있으면 false. GPT 시도 전에 호출한다. */
	public boolean gptPermitted() {
		return breaker.tryAcquirePermission();
	}

	public void gptSucceeded(long nanos) {
		breaker.onSuccess(nanos, java.util.concurrent.TimeUnit.NANOSECONDS);
	}

	public void gptFailed(long nanos, Throwable t) {
		breaker.onError(nanos, java.util.concurrent.TimeUnit.NANOSECONDS, t);
	}

	public Flux<ChatResponse> stream(Provider provider, String system, List<Message> history, String userText,
		ToolCallback[] tools, Map<String, Object> toolContext) {
		ChatClient client = provider == Provider.GPT ? openAi : claude;
		return client.prompt()
			.system(system)
			.messages(history)
			.user(userText)
			.toolCallbacks(tools)
			.toolContext(toolContext)
			.stream()
			.chatResponse();
	}

	/** 짧은 단발 호출(대화 제목 생성 등). 실패하면 null. */
	public String complete(String system, String user) {
		for (Provider p : new Provider[] {Provider.GPT, Provider.CLAUDE}) {
			if (p == Provider.GPT && !gptPermitted()) {
				continue;
			}
			long started = System.nanoTime();
			try {
				String out = (p == Provider.GPT ? openAi : claude).prompt().system(system).user(user).call().content();
				if (p == Provider.GPT) {
					gptSucceeded(System.nanoTime() - started);
				}
				return out;
			} catch (RuntimeException e) {
				if (p == Provider.GPT) {
					gptFailed(System.nanoTime() - started, e);
				}
				log.info("{} 단발 호출 실패: {}", p, e.toString());
			}
		}
		return null;
	}
}
