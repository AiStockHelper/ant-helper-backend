package com.backend.domains.chat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.backend.common.exception.ApiException;
import com.backend.common.exception.ErrorCode;
import com.backend.common.properties.AppProperties;
import com.backend.common.util.Json;
import com.backend.domains.account.AccountService;
import com.backend.domains.chat.ai.LlmGateway;
import com.backend.domains.chat.ai.LlmGateway.Provider;
import com.backend.domains.chat.ai.SystemPromptFactory;
import com.backend.domains.chat.memory.ConversationMemory;
import com.backend.domains.chat.dto.ChatDtos.Action;
import com.backend.domains.chat.dto.ChatDtos.Card;
import com.backend.domains.chat.dto.ChatDtos.Message;
import com.backend.domains.chat.dto.ChatDtos.SendMessageRequest;
import com.backend.domains.chat.dto.ChatDtos.ToolCall;
import com.backend.domains.chat.entity.ChatConversation;
import com.backend.domains.chat.entity.ChatMessage;
import com.backend.domains.chat.repository.ChatActionRepository;
import com.backend.domains.chat.repository.ChatConversationRepository;
import com.backend.domains.chat.repository.ChatMessageRepository;
import com.backend.domains.chat.tool.ChatSession;
import com.backend.domains.chat.tool.ChatTools;
import com.backend.domains.chat.tool.EventingToolCallback;
import com.backend.domains.member.domain.MemberAccount;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.Disposable;

/**
 * 사용자 메시지를 저장하고 LLM 응답을 SSE 로 흘려 보낸다.
 * 클라이언트가 연결을 끊으면 생성을 중단하고 지금까지의 내용을 STOPPED 로 저장한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatStreamService {

	private final ChatConversationRepository conversations;
	private final ChatMessageRepository messages;
	private final ChatActionRepository actionRepository;
	private final ChatMapper mapper;
	private final ChatService chatService;
	private final AccountService accountService;
	private final LlmGateway llm;
	private final SystemPromptFactory promptFactory;
	private final ConversationMemory memory;
	private final ChatTools chatTools;
	private final AppProperties props;
	private final Json json;
	private final TransactionTemplate tx;
	private final com.backend.common.logging.ErrorEventRecorder recorder;

	private final Set<Long> active = ConcurrentHashMap.newKeySet();
	private ToolCallback[] toolCallbacks;

	@PostConstruct
	void init() {
		ToolCallback[] raw = ToolCallbacks.from(chatTools);
		toolCallbacks = new ToolCallback[raw.length];
		for (int i = 0; i < raw.length; i++) {
			toolCallbacks[i] = new EventingToolCallback(raw[i], json.mapper());
		}
		log.info("AI 도구 {}개 등록", toolCallbacks.length);
	}

	/** 서버가 응답 생성 도중 종료되면 STREAMING 으로 남은 메시지가 있을 수 있어 시작 시 ERROR 로 정리한다. */
	@org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
	void cleanUpInterruptedStreams() {
		Integer n = tx.execute(s -> messages.markInterruptedStreams());
		if (n != null && n > 0) {
			log.warn("이전 실행에서 중단된 응답 {}건을 ERROR 로 정리했습니다.", n);
		}
	}

	// ─────────────────────────── 진입점 ───────────────────────────

	/** 새 메시지(또는 수정된 메시지)에 대한 답변 스트림 */
	public SseEmitter send(Long memberId, Long conversationId, SendMessageRequest req) {
		ChatConversation conv = chatService.require(memberId, conversationId);
		String content = req.content() == null ? "" : req.content().trim();
		if (content.isEmpty()) {
			throw ApiException.from(ErrorCode.CHAT_EMPTY_MESSAGE);
		}
		if (req.accountId() != null) {
			accountService.requireOwned(memberId, req.accountId());
		}
		lock(conversationId);
		try {
			Prepared p = tx.execute(s -> {
				ChatConversation c = conversations.findById(conversationId).orElseThrow();
				if (req.accountId() != null) {
					c.useAccount(req.accountId());
				}
				if (req.editMessageId() != null) {
					ChatMessage target = messages.findById(req.editMessageId())
						.filter(m -> m.getConversationId().equals(conversationId) && m.getRole() == ChatMessage.Role.USER)
						.orElseThrow(() -> ApiException.from(ErrorCode.CHAT_MESSAGE_NOT_FOUND));
					deleteFrom(conversationId, target.getId());
				}
				ChatMessage user = messages.save(ChatMessage.user(conversationId, memberId, content));
				ChatMessage assistant = messages.save(ChatMessage.assistantPlaceholder(conversationId, memberId));
				c.updatePreview(content);
				c.touch();
				return new Prepared(c, user, assistant);
			});
			return start(memberId, tx.execute(s -> conversations.findById(conversationId).orElseThrow()), p.user, p.assistant, content);
		} catch (RuntimeException e) {
			unlock(conversationId);
			throw e;
		}
	}

	/** 마지막 사용자 메시지에 대한 답변을 다시 생성한다. */
	public SseEmitter regenerate(Long memberId, Long conversationId) {
		chatService.require(memberId, conversationId);
		lock(conversationId);
		try {
			Prepared p = tx.execute(s -> {
				ChatConversation c = conversations.findById(conversationId).orElseThrow();
				List<ChatMessage> all = messages.findByConversationIdOrderByIdAsc(conversationId);
				ChatMessage lastUser = null;
				for (ChatMessage m : all) {
					if (m.getRole() == ChatMessage.Role.USER) {
						lastUser = m;
					}
				}
				if (lastUser == null) {
					throw ApiException.from(ErrorCode.CHAT_MESSAGE_NOT_FOUND);
				}
				deleteFrom(conversationId, lastUser.getId() + 1);
				ChatMessage assistant = messages.save(ChatMessage.assistantPlaceholder(conversationId, memberId));
				c.touch();
				return new Prepared(c, lastUser, assistant);
			});
			return start(memberId, p.conversation, p.user, p.assistant, p.user.getContent());
		} catch (RuntimeException e) {
			unlock(conversationId);
			throw e;
		}
	}

	private record Prepared(ChatConversation conversation, ChatMessage user, ChatMessage assistant) {
	}

	private void deleteFrom(Long conversationId, Long fromId) {
		List<Long> ids = messages.findByConversationIdOrderByIdAsc(conversationId).stream()
			.map(ChatMessage::getId).filter(id -> id >= fromId).toList();
		if (!ids.isEmpty()) {
			actionRepository.deleteByMessageIds(ids);
			messages.deleteFrom(conversationId, fromId);
		}
	}

	private void lock(Long conversationId) {
		if (!active.add(conversationId)) {
			throw ApiException.from(ErrorCode.CHAT_STREAM_BUSY);
		}
	}

	private void unlock(Long conversationId) {
		active.remove(conversationId);
	}

	// ─────────────────────────── 스트리밍 ───────────────────────────

	private SseEmitter start(Long memberId, ChatConversation conv, ChatMessage user, ChatMessage assistant, String userText) {
		long timeoutMs = (props.getLlm().getStreamTimeoutSeconds() + 60L) * 1000L;
		SseEmitter emitter = new SseEmitter(timeoutMs);
		Run run = new Run(memberId, conv.getId(), user, assistant, userText, emitter);
		emitter.onCompletion(run::cancel);
		emitter.onTimeout(run::cancel);
		emitter.onError(e -> run.cancel());

		Message userDto = mapper.message(user, List.of());
		run.send("meta", Map.of("conversation", mapper.conversation(conv), "userMessage", userDto,
			"assistantMessageId", assistant.getId()));
		final String reqId = org.slf4j.MDC.get("reqId");
		Thread.ofVirtual().name("chat-stream-" + assistant.getId()).start(() -> {
			org.slf4j.MDC.put("memberId", String.valueOf(memberId));
			if (reqId != null) {
				org.slf4j.MDC.put("reqId", reqId);
			}
			long started = System.currentTimeMillis();
			try {
				log.info("채팅 응답 생성 시작 conversation={} assistantMessage={} chars={}", conv.getId(), assistant.getId(),
					userText == null ? 0 : userText.length());
				run.execute();
				log.info("채팅 응답 생성 종료 conversation={} assistantMessage={} {}ms", conv.getId(), assistant.getId(),
					System.currentTimeMillis() - started);
			} catch (Throwable t) {
				log.error("채팅 스트림 처리 중 예기치 않은 오류 conversation={}: {}", conv.getId(), t.toString(), t);
				recorder.record("CHAT", null, "CHAT_STREAM_ERROR", t.getMessage(), t, "conversation=" + conv.getId());
				run.fail("INTERNAL", "답변을 만드는 중 오류가 발생했습니다.");
			} finally {
				unlock(conv.getId());
				org.slf4j.MDC.remove("memberId");
				org.slf4j.MDC.remove("reqId");
			}
		});
		return emitter;
	}

	/** 한 번의 답변 생성 작업의 상태 */
	private final class Run implements ChatSession.Sink {

		final Long memberId;
		final Long conversationId;
		final ChatMessage userMessage;
		final ChatMessage assistantMessage;
		final String userText;
		final SseEmitter emitter;

		final StringBuilder text = new StringBuilder();
		final Map<String, ToolCall> toolCalls = new LinkedHashMap<>();
		final List<Card> cards = new ArrayList<>();
		final List<Action> actions = new ArrayList<>();
		volatile boolean cancelled;
		volatile boolean finished;
		final AtomicReference<Disposable> subscription = new AtomicReference<>();
		/** 현재 대기 중인 소비 루프를 즉시 깨우기 위한 래치. dispose 만으로는 완료 신호가 오지 않는다. */
		volatile CountDownLatch waiting;

		Run(Long memberId, Long conversationId, ChatMessage userMessage, ChatMessage assistantMessage, String userText,
			SseEmitter emitter) {
			this.memberId = memberId;
			this.conversationId = conversationId;
			this.userMessage = userMessage;
			this.assistantMessage = assistantMessage;
			this.userText = userText;
			this.emitter = emitter;
		}

		void cancel() {
			if (!finished) {
				cancelled = true;
			}
			Disposable d = subscription.get();
			if (d != null && !d.isDisposed()) {
				d.dispose();
			}
			CountDownLatch w = waiting;
			if (w != null) {
				w.countDown();
			}
		}

		// Sink
		@Override
		public void tool(ToolCall call) {
			log.info("AI 도구 호출 member={} conversation={} tool={} status={}", memberId, conversationId, call.name(),
				call.status());
			synchronized (toolCalls) {
				toolCalls.put(call.id(), call);
			}
			send("tool", call);
		}

		@Override
		public void card(Card card) {
			synchronized (cards) {
				cards.add(card);
			}
			send("card", card);
		}

		@Override
		public void action(Action action) {
			synchronized (actions) {
				actions.add(action);
			}
			send("action", action);
		}

		boolean send(String event, Object data) {
			if (cancelled) {
				return false;
			}
			synchronized (emitter) {
				try {
					emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
					return true;
				} catch (IOException | IllegalStateException e) {
					cancel();
					return false;
				}
			}
		}

		void ping() {
			synchronized (emitter) {
				try {
					emitter.send(SseEmitter.event().comment("keep-alive"));
				} catch (IOException | IllegalStateException e) {
					cancel();
				}
			}
		}

		void execute() {
			MemberAccount account = resolveAccountOrNull();
			ConversationMemory.Prepared memoryContext = memory.prepare(conversationId, userMessage.getId());
			String system = promptFactory.build(account, memoryContext.contextBlock());
			List<org.springframework.ai.chat.messages.Message> history = memoryContext.window();
			ChatSession session = new ChatSession(memberId, conversationId, assistantMessage.getId(), this,
				() -> accountService.resolve(memberId, currentAccountId()));
			Map<String, Object> toolContext = Map.of("session", session);

			Provider used = null;
			Throwable lastError = null;
			boolean produced = false;
			for (Provider provider : new Provider[] {Provider.GPT, Provider.CLAUDE}) {
				if (cancelled) {
					break;
				}
				if (provider == Provider.GPT && !llm.gptPermitted()) {
					continue;
				}
				long started = System.nanoTime();
				Throwable error = consume(provider, system, history, toolContext);
				long elapsed = System.nanoTime() - started;
				produced = text.length() > 0 || !toolCalls.isEmpty() || !cards.isEmpty() || !actions.isEmpty();
				if (provider == Provider.GPT) {
					if (error == null || cancelled) {
						llm.gptSucceeded(elapsed);
					} else {
						llm.gptFailed(elapsed, error);
					}
				}
				if (cancelled) {
					break;
				}
				if (error == null) {
					used = provider;
					lastError = null;
					break;
				}
				lastError = error;
				log.warn("{} 응답 실패: {}", provider, error.toString());
				if (produced) {
					break;
				}
			}

			if (cancelled) {
				persist(ChatMessage.Status.STOPPED, used);
				finished = true;
				return;
			}
			if (used == null) {
				log.error("AI 응답 실패(모든 공급자) member={} conversation={} produced={} lastError={}", memberId,
					conversationId, produced, lastError == null ? "-" : lastError.toString());
				recorder.record("CHAT", null, "LLM-001", "AI 응답을 가져오지 못했습니다", lastError,
					"member=" + memberId + " conversation=" + conversationId);
				persist(ChatMessage.Status.ERROR, null);
				finished = true;
				send("error", Map.of("code", "LLM-001", "message",
					produced ? "답변을 만드는 도중 연결이 끊어졌습니다. 다시 시도해 주세요."
						: "AI 응답을 가져오지 못했습니다. 잠시 후 다시 시도해 주세요."));
				complete();
				return;
			}
			Message doneMessage = persist(ChatMessage.Status.COMPLETE, used);
			finished = true;
			send("done", Map.of("message", doneMessage));
			maybeTitle();
			complete();
			// 응답을 끝낸 뒤 별도 스레드에서 오래된 대화를 요약한다(다음 메시지를 막지 않도록).
			Thread.ofVirtual().name("chat-summary-" + conversationId).start(() -> memory.summarizeIfNeeded(conversationId));
			if (lastError != null) {
				log.info("폴백 후 응답 완료({})", used);
			}
		}

		/** 한 공급자로 스트림을 끝까지 소비한다. 정상 종료면 null, 실패면 원인을 돌려준다. */
		Throwable consume(Provider provider, String system, List<org.springframework.ai.chat.messages.Message> history,
			Map<String, Object> toolContext) {
			CountDownLatch latch = new CountDownLatch(1);
			waiting = latch;
			AtomicReference<Throwable> error = new AtomicReference<>();
			Disposable d;
			try {
				d = llm.stream(provider, system, history, userText, toolCallbacks, toolContext).subscribe(
					chunk -> onChunk(chunk),
					e -> {
						error.set(e);
						latch.countDown();
					},
					latch::countDown);
			} catch (RuntimeException e) {
				return e;
			}
			subscription.set(d);
			if (cancelled) {
				// 구독을 시작하는 사이에 이미 취소된 경우
				d.dispose();
				return null;
			}
			long deadline = System.currentTimeMillis() + props.getLlm().getStreamTimeoutSeconds() * 1000L;
			try {
				while (!latch.await(10, TimeUnit.SECONDS)) {
					if (cancelled) {
						d.dispose();
						return null;
					}
					if (System.currentTimeMillis() > deadline) {
						d.dispose();
						return new IllegalStateException("응답 시간이 초과되었습니다.");
					}
					ping();
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				d.dispose();
				return e;
			}
			return error.get();
		}

		void onChunk(ChatResponse chunk) {
			Generation g = chunk.getResult();
			if (g == null || g.getOutput() == null) {
				return;
			}
			String delta = g.getOutput().getText();
			if (delta == null || delta.isEmpty()) {
				return;
			}
			text.append(delta);
			send("delta", Map.of("text", delta));
		}

		Message persist(ChatMessage.Status status, Provider provider) {
			String model = provider == null ? null : llm.modelName(provider);
			List<ToolCall> tools;
			synchronized (toolCalls) {
				tools = new ArrayList<>(toolCalls.values());
			}
			List<Card> cardList;
			synchronized (cards) {
				cardList = new ArrayList<>(cards);
			}
			String body = text.toString();
			if (status == ChatMessage.Status.COMPLETE && body.isBlank() && cardList.isEmpty() && actions.isEmpty()) {
				body = "(응답이 비어 있습니다. 다시 시도해 주세요.)";
			}
			final String finalBody = body;
			return tx.execute(s -> {
				ChatMessage m = messages.findById(assistantMessage.getId()).orElse(null);
				if (m == null) {
					return null;
				}
				m.finish(finalBody, status, json.write(tools), json.write(cardList), model);
				conversationOf().ifPresent(c -> {
					if (!finalBody.isBlank()) {
						c.updatePreview(finalBody);
					}
					c.touch();
				});
				List<Action> acts = actionRepository.findByMessageId(m.getId()).stream().map(mapper::action).toList();
				return mapper.message(m, acts);
			});
		}

		java.util.Optional<ChatConversation> conversationOf() {
			return conversations.findById(conversationId);
		}

		Long currentAccountId() {
			return conversationOf().map(ChatConversation::getAccountId).orElse(null);
		}

		MemberAccount resolveAccountOrNull() {
			Long accountId = currentAccountId();
			if (accountId != null) {
				try {
					return accountService.resolve(memberId, accountId);
				} catch (ApiException e) {
					// 대화에 지정된 계좌가 삭제된 경우: 기본 계좌로 대체한다.
				}
			}
			return accountService.findDefault(memberId).orElse(null);
		}

		void maybeTitle() {
			try {
				ChatConversation c = conversationOf().orElse(null);
				if (c == null || !c.hasDefaultTitle()) {
					return;
				}
				String title = generateTitle();
				tx.executeWithoutResult(s -> conversations.findById(conversationId).ifPresent(x -> x.rename(title)));
				send("title", Map.of("title", title));
			} catch (RuntimeException e) {
				log.info("제목 생성 실패: {}", e.getMessage());
			}
		}

		String generateTitle() {
			String answer = text.toString();
			String raw = llm.complete(
				"대화 제목을 만드는 도우미입니다. 사용자의 첫 질문과 답변을 보고 12자 이내의 한국어 명사구 제목 한 줄만 출력하세요. 따옴표, 마침표, 설명 없이 제목만.",
				"질문: " + truncate(userText, 300) + "\n답변: " + truncate(answer, 300));
			String cleaned = raw == null ? "" : raw.replaceAll("[\"'“”‘’`]", "").replaceAll("\\s+", " ").trim();
			if (cleaned.isEmpty() || cleaned.length() > 40) {
				cleaned = truncate(userText.replaceAll("\\s+", " ").trim(), 24);
			}
			return cleaned;
		}

		void fail(String code, String message) {
			finished = true;
			try {
				persist(ChatMessage.Status.ERROR, null);
			} catch (RuntimeException ignored) {
				// 저장 실패는 무시하고 사용자에게 오류만 알린다.
			}
			send("error", Map.of("code", code, "message", message));
			complete();
		}

		void complete() {
			synchronized (emitter) {
				try {
					emitter.complete();
				} catch (RuntimeException ignored) {
					// 이미 종료된 연결
				}
			}
		}
	}

	private static String truncate(String s, int max) {
		return s.length() <= max ? s : s.substring(0, max);
	}
}
