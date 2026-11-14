package com.backend.domains.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import com.backend.common.properties.AppProperties;
import com.backend.common.util.Json;
import com.backend.domains.chat.ai.LlmGateway;
import com.backend.domains.chat.ai.LlmGateway.Completion;
import com.backend.domains.chat.dto.ChatDtos.Action;
import com.backend.domains.chat.dto.ChatDtos.Card;
import com.backend.domains.chat.dto.ChatDtos.ToolCall;
import com.backend.domains.chat.tool.ChatSession;
import com.backend.domains.chat.tool.ChatTools;
import com.backend.domains.chat.tool.EventingToolCallback;
import com.backend.domains.member.domain.MemberAccount;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 지시문(instruction)으로 정의된 자동 실행을 AI 가 도구로 수행하게 한다. 스트리밍·사람 없이 한 번에 돌린다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentLlmRunner {

	private final LlmGateway llm;
	private final ChatTools chatTools;
	private final AgentPromptFactory prompts;
	private final AgentTradingService trading;
	private final AgentRunRepository runs;
	private final AgentTaskService taskService;
	private final AppProperties props;
	private final Json json;

	private ToolCallback[] callbacks;

	@PostConstruct
	void init() {
		ToolCallback[] raw = ToolCallbacks.from(chatTools);
		callbacks = new ToolCallback[raw.length];
		for (int i = 0; i < raw.length; i++) {
			callbacks[i] = new EventingToolCallback(raw[i], json.mapper());
		}
	}

	public RunOutcome run(AgentTask task, MemberAccount account, boolean manual) {
		boolean autonomous = task.isAutonomous() || account.isPaper();
		List<Card> cards = new ArrayList<>();
		Map<String, ToolCall> tools = new LinkedHashMap<>();
		ChatSession.Sink sink = new ChatSession.Sink() {
			@Override
			public void tool(ToolCall call) {
				synchronized (tools) {
					tools.put(call.id(), call);
				}
			}

			@Override
			public void card(Card card) {
				synchronized (cards) {
					cards.add(card);
				}
			}

			@Override
			public void action(Action action) {
				// 자동 실행에서는 확인 카드를 만들지 않는다.
			}
		};
		ChatSession session = new ChatSession(task.getMemberId(), task.getConversationId(), null, sink, () -> account)
			.bindAgent(task.getId(), autonomous, props.getAgent().getMaxToolCalls(), 30);

		List<String> recent = runs.findTop5ByTaskIdAndStatusOrderByIdDesc(task.getId(), AgentRun.Status.DONE).stream()
			.map(r -> "- " + r.getFinishedAt().atZone(com.backend.common.util.TimeUtil.SEOUL)
				.format(java.time.format.DateTimeFormatter.ofPattern("M/d HH:mm")) + " " + oneLine(r.getSummary(), 240))
			.toList();
		String system = prompts.build(task, taskService.scheduleOf(task), account, autonomous, trading.summaryText(task), recent,
			props.getAgent().getMaxToolCalls());
		String user = manual
			? "사용자가 '지금 실행'을 눌러 수동으로 시작했습니다. 지시에 따라 작업을 수행하고 결과를 보고하세요."
			: "예약된 실행 시각이 되었습니다. 지시에 따라 작업을 수행하고 결과를 보고하세요.";

		final String reqId = org.slf4j.MDC.get("reqId");
		CompletableFuture<Completion> future = new CompletableFuture<>();
		Thread.ofVirtual().name("agent-llm-" + task.getId()).start(() -> {
			org.slf4j.MDC.put("memberId", String.valueOf(task.getMemberId()));
			if (reqId != null) {
				org.slf4j.MDC.put("reqId", reqId);
			}
			try {
				future.complete(llm.callWithTools(system, user, callbacks, Map.of("session", session),
					() -> session.ordersAccepted() > 0));
			} catch (Throwable t) {
				future.completeExceptionally(t);
			} finally {
				org.slf4j.MDC.remove("memberId");
				org.slf4j.MDC.remove("reqId");
			}
		});

		Completion completion;
		try {
			completion = future.get(props.getAgent().getLlmTimeoutSeconds(), TimeUnit.SECONDS);
		} catch (TimeoutException e) {
			future.cancel(true);
			return withEvents(RunOutcome.failed("AI 응답 시간이 " + props.getAgent().getLlmTimeoutSeconds() + "초를 넘어 중단했습니다."),
				tools, cards, session);
		} catch (ExecutionException e) {
			log.warn("자동 실행 AI 호출 실패 task={}: {}", task.getId(), e.getCause() == null ? e : e.getCause().toString());
			return withEvents(RunOutcome.failed("AI 응답을 가져오지 못했습니다. 잠시 후 다시 시도됩니다."), tools, cards, session);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return RunOutcome.failed("실행이 중단되었습니다.");
		}

		String text = completion.text().isBlank() ? "(AI가 결과 요약을 남기지 않았습니다.)" : completion.text().trim();
		return new RunOutcome(AgentRun.Status.DONE, text, toolEvents(tools), List.copyOf(cards),
			llm.modelName(completion.provider()), false, null, session.ordersAccepted());
	}

	private RunOutcome withEvents(RunOutcome base, Map<String, ToolCall> tools, List<Card> cards, ChatSession session) {
		return new RunOutcome(base.status(), base.summary(), toolEvents(tools), List.copyOf(cards), null, false, null,
			session.ordersAccepted());
	}

	private List<String> toolEvents(Map<String, ToolCall> tools) {
		List<String> out = new ArrayList<>();
		synchronized (tools) {
			for (ToolCall t : tools.values()) {
				if (out.size() >= 40) {
					break;
				}
				String tail = t.summary() == null || t.summary().isBlank() ? "" : " → " + t.summary();
				out.add((t.status().equals("ERROR") ? "⚠ " : "") + t.label() + tail);
			}
		}
		return out;
	}

	private static String oneLine(String s, int max) {
		if (s == null) {
			return "";
		}
		String flat = s.replaceAll("\\s+", " ").trim();
		return flat.length() > max ? flat.substring(0, max) + "…" : flat;
	}
}
