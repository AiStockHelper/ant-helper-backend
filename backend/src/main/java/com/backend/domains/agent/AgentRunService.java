package com.backend.domains.agent;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.backend.common.exception.ApiException;
import com.backend.common.exception.ErrorCode;
import com.backend.common.logging.ErrorEventRecorder;
import com.backend.common.properties.AppProperties;
import com.backend.common.util.Json;
import com.backend.common.util.TimeUtil;
import com.backend.domains.agent.skill.Skill;
import com.backend.domains.agent.skill.SkillContext;
import com.backend.domains.agent.skill.SkillRegistry;
import com.backend.domains.chat.dto.ChatDtos.Card;
import com.backend.domains.member.domain.MemberAccount;
import com.backend.domains.member.repository.MemberAccountRepository;
import com.backend.domains.trade.TradePlanner;
import com.backend.market.MarketDataService;
import com.backend.market.PredictionService;
import com.backend.market.TradingCalendar;
import com.backend.market.dto.MarketDtos.OrderOutcome;
import com.backend.order.kis.kis_client.exception.KisClientException;
import com.fasterxml.jackson.core.type.TypeReference;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 자동 실행 작업 한 건을 실제로 실행하고 결과를 반영한다.
 * 한 번만 실행되도록 선점(claim)한 뒤 스킬 코드 또는 AI 로 처리하고, 다음 실행 시각을 계산한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentRunService {

	private static final DateTimeFormatter HUMAN = DateTimeFormatter.ofPattern("M월 d일 HH:mm");

	private final AgentTaskRepository tasks;
	private final AgentRunRepository runs;
	private final MemberAccountRepository accounts;
	private final SkillRegistry registry;
	private final AgentLlmRunner llmRunner;
	private final AgentReporter reporter;
	private final AgentTaskService taskService;
	private final AgentTradingService trading;
	private final TradingCalendar calendar;
	private final MarketDataService marketData;
	private final PredictionService prediction;
	private final TradePlanner planner;
	private final AppProperties props;
	private final Json json;
	private final TransactionTemplate tx;
	private final ErrorEventRecorder recorder;

	/** 서버가 실행 도중 종료된 경우 RUNNING 으로 남은 작업을 되살린다. 중단된 실행은 다시 하지 않고 다음 일정으로 넘긴다. */
	@EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
	void recover() {
		tx.executeWithoutResult(s -> {
			for (AgentTask t : tasks.findByStatus(AgentTask.Status.RUNNING)) {
				try {
					Schedule sch = taskService.scheduleOf(t);
					Instant next = sch.type() == Schedule.Type.ONCE ? null : sch.next(Instant.now(), calendar).orElse(null);
					t.finishRun(Instant.now(), "서버가 재시작되어 이전 실행이 중단되었습니다. 다음 일정부터 이어갑니다.", null, next,
						sch.type() == Schedule.Type.ONCE);
					log.warn("중단된 자동 실행 복구 task={}", t.getId());
				} catch (RuntimeException e) {
					t.pause("복구 중 오류: " + e.getMessage());
				}
			}
		});
	}

	/** '지금 실행' 버튼. 백그라운드에서 실행하고 바로 돌아온다. */
	public void runNow(Long memberId, Long taskId) {
		AgentTask t = taskService.require(memberId, taskId);
		if (!props.getAgent().isEnabled()) {
			throw ApiException.from(ErrorCode.AGENT_DISABLED);
		}
		if (t.getStatus() != AgentTask.Status.ACTIVE) {
			throw ApiException.of(ErrorCode.AGENT_STATE_CONFLICT, "실행 대기 중인 작업만 지금 실행할 수 있습니다.");
		}
		Thread.ofVirtual().name("agent-manual-" + taskId).start(() -> {
			try {
				execute(taskId, true);
			} catch (RuntimeException e) {
				log.error("수동 실행 중 오류 task={}", taskId, e);
			}
		});
	}

	public void execute(Long taskId, boolean manual) {
		Boolean claimed = tx.execute(s -> tasks.claim(taskId) == 1);
		if (!Boolean.TRUE.equals(claimed)) {
			return;
		}
		AgentTask task = tasks.findById(taskId).orElse(null);
		if (task == null) {
			return;
		}
		org.slf4j.MDC.put("memberId", String.valueOf(task.getMemberId()));
		org.slf4j.MDC.put("reqId", "agent-" + taskId);
		Instant started = Instant.now();
		Map<String, Object> state = json.read(task.getStateJson(), new TypeReference<Map<String, Object>>() {},
			new LinkedHashMap<>());
		state = new LinkedHashMap<>(state);
		RunOutcome outcome;
		try {
			log.info("자동 실행 시작 task={} skill={} manual={} 예정={}", taskId, task.getSkillId(), manual, task.getNextRunAt());
			outcome = runTask(task, manual, state);
		} catch (RuntimeException e) {
			boolean known = e instanceof ApiException || e instanceof KisClientException;
			String msg = known ? e.getMessage() : "실행 중 오류가 발생했습니다: " + e.getMessage();
			if (known) {
				log.warn("자동 실행 실패 task={}: {}", taskId, msg);
			} else {
				log.error("자동 실행 중 예기치 않은 오류 task={}", taskId, e);
			}
			outcome = RunOutcome.failed(msg);
		}
		try {
			finish(task, outcome, started, manual, state);
		} finally {
			org.slf4j.MDC.remove("memberId");
			org.slf4j.MDC.remove("reqId");
		}
	}

	private RunOutcome runTask(AgentTask task, boolean manual, Map<String, Object> state) {
		if (!props.getAgent().isEnabled()) {
			return RunOutcome.skipped("자동 실행이 서버 설정으로 꺼져 있어 건너뛰었습니다.");
		}
		if (!manual && task.getNextRunAt() != null) {
			Duration late = Duration.between(task.getNextRunAt(), Instant.now());
			if (late.toMinutes() > props.getAgent().getMaxLatenessMinutes()) {
				return RunOutcome.skipped("예정 시각(" + task.getNextRunAt().atZone(TimeUtil.SEOUL).format(HUMAN) + ")보다 "
					+ late.toMinutes() + "분 넘게 늦어져 안전을 위해 이번 실행은 건너뛰었습니다.");
			}
		}
		MemberAccount account = accounts.findById(task.getAccountId()).orElse(null);
		if (account == null || !account.getMemberId().equals(task.getMemberId())) {
			return RunOutcome.failed("연결된 증권 계좌를 찾을 수 없습니다.");
		}
		if (task.getSkillId() == null) {
			return llmRunner.run(task, account, manual);
		}

		Skill skill = registry.find(task.getSkillId());
		if (skill == null) {
			return RunOutcome.failed("더 이상 제공되지 않는 스킬입니다: " + task.getSkillId());
		}
		Map<String, Object> config = json.read(task.getConfigJson(), new TypeReference<Map<String, Object>>() {}, Map.of());
		SkillContext ctx = new SkillContext(task, account, config, state, manual,
			new SkillContext.Services(trading, marketData, prediction, planner, calendar));
		skill.run(ctx);

		List<String> events = new ArrayList<>(ctx.events());
		List<Card> cards = ctx.outcomes().stream().map(o -> new Card("order_result", OrderCards.orderResult(o))).toList();
		int accepted = (int)ctx.outcomes().stream().filter(OrderOutcome::success).count();
		boolean quiet = events.isEmpty() && ctx.outcomes().isEmpty() && ctx.pauseReason() == null;
		String summary = events.isEmpty() ? "특별한 일이 없었습니다." : "- " + String.join("\n- ", events);
		return new RunOutcome(AgentRun.Status.DONE, summary, events, cards, null, quiet, ctx.pauseReason(), accepted);
	}

	private void finish(AgentTask task, RunOutcome outcome, Instant started, boolean manual, Map<String, Object> state) {
		Instant finished = Instant.now();
		boolean failed = outcome.status() == AgentRun.Status.FAILED;
		boolean[] pausedByFailure = {false};
		String[] pauseMessage = {null};

		tx.executeWithoutResult(s -> {
			AgentTask t = tasks.findById(task.getId()).orElse(null);
			if (t == null) {
				return;
			}
			Schedule schedule = taskService.scheduleOf(t);
			boolean once = schedule.type() == Schedule.Type.ONCE;
			Instant next = once ? null : schedule.next(finished, calendar).orElse(null);
			Boolean success = outcome.status() == AgentRun.Status.SKIPPED ? null : !failed;
			t.finishRun(finished, shorten(outcome.summary(), 4000), success, next, once);
			if (task.getSkillId() != null) {
				t.saveState(json.write(state));
			}
			if (outcome.pauseReason() != null && t.getStatus() == AgentTask.Status.ACTIVE) {
				t.pause(outcome.pauseReason());
				pauseMessage[0] = outcome.pauseReason();
			} else if (failed && t.getStatus() == AgentTask.Status.ACTIVE
				&& t.getFailCount() >= props.getAgent().getMaxConsecutiveFailures()) {
				pauseMessage[0] = "연속 " + t.getFailCount() + "회 실패해 자동으로 일시정지했습니다. 마지막 오류: "
					+ shorten(outcome.summary(), 300);
				t.pause(pauseMessage[0]);
				pausedByFailure[0] = true;
			}
			if (!outcome.quiet() || failed) {
				runs.save(new AgentRun(t.getId(), t.getMemberId(), outcome.status(), manual, started, finished,
					shorten(outcome.summary(), 8000), json.write(outcome.events()), outcome.model()));
			}
		});

		if (failed) {
			recorder.record("AGENT", task.getAccountId(), "AGENT_FAILED", outcome.summary(), null,
				"taskId=" + task.getId() + " skill=" + task.getSkillId() + " title=" + task.getTitle());
		}
		log.info("자동 실행 종료 task={} status={} quiet={} orders={} {}ms", task.getId(), outcome.status(), outcome.quiet(),
			outcome.ordersAccepted(), Duration.between(started, finished).toMillis());

		AgentTask fresh = tasks.findById(task.getId()).orElse(null);
		MemberAccount account = accounts.findById(task.getAccountId()).orElse(null);
		if (fresh == null || account == null) {
			return;
		}
		if (!outcome.quiet() || failed) {
			String head = (failed ? "⚠️ **자동 실행 실패**" : outcome.status() == AgentRun.Status.SKIPPED ? "⏭️ **자동 실행 건너뜀**"
				: "🤖 **자동 실행 결과**") + " — " + task.getTitle() + (manual ? " (수동 실행)" : "");
			String body = outcome.summary();
			if (pauseMessage[0] != null) {
				body += "\n\n⏸ 일시정지: " + pauseMessage[0];
			}
			reporter.post(fresh, head, body, outcome.cards());
		}
		if (pauseMessage[0] != null) {
			reporter.email(fresh, account, "자동 실행이 일시정지되었습니다", pauseMessage[0]);
		} else if (!account.isPaper() && outcome.ordersAccepted() > 0) {
			reporter.email(fresh, account, "실전 계좌 자동 주문 " + outcome.ordersAccepted() + "건 접수", outcome.summary());
		}
	}

	private static String shorten(String s, int max) {
		if (s == null) {
			return null;
		}
		return s.length() > max ? s.substring(0, max) + "…" : s;
	}
}
