package com.backend.domains.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.backend.common.exception.ApiException;
import com.backend.common.exception.ErrorCode;
import com.backend.common.properties.AppProperties;
import com.backend.common.util.Json;
import com.backend.domains.account.AccountService;
import com.backend.domains.agent.AgentDtos.UpdateTaskRequest;
import com.backend.domains.agent.AgentTradingService.LedgerView;
import com.backend.domains.agent.skill.MultiFactorSkill;
import com.backend.domains.agent.skill.SkillRegistry;
import com.backend.domains.auth.otp.StepUpService;
import com.backend.domains.chat.repository.ChatConversationRepository;
import com.backend.domains.factor.FactorSnapshotRepository;
import com.backend.domains.member.repository.MemberAccountRepository;
import com.backend.market.FactorDataService;
import com.backend.market.TradingCalendar;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 작업 수정(설정·한도·일정): 돈과 관련된 경계 조건을 확인한다. */
class AgentTaskEditTest {

	private static final long MEMBER = 1L;
	private static final long TASK_ID = 7L;

	private final Json json = new Json(new ObjectMapper().findAndRegisterModules());
	private AgentTaskRepository tasks;
	private AgentTradingService trading;
	private TradingCalendar calendar;
	private MultiFactorSkill skill;
	private AgentTaskService service;

	@BeforeEach
	void setUp() {
		tasks = mock(AgentTaskRepository.class);
		trading = mock(AgentTradingService.class);
		calendar = mock(TradingCalendar.class);
		when(calendar.isTradingDay(any(), any())).thenReturn(true);
		skill = new MultiFactorSkill(mock(FactorDataService.class), mock(FactorSnapshotRepository.class));
		service = new AgentTaskService(tasks, mock(AgentRunRepository.class), mock(MemberAccountRepository.class),
			mock(AccountService.class), mock(ChatConversationRepository.class), new SkillRegistry(List.of(skill)), calendar,
			trading, new AppProperties(), json, mock(StepUpService.class));
	}

	private static Limits limits(String budget) {
		BigDecimal b = new BigDecimal(budget);
		return new Limits(b, "KRW", 30.0, b, 15.0);
	}

	private AgentTask task(String skillId, Limits limits, String configJson, Long parentId) {
		String scheduleJson = json.write(Schedule.marketInterval("KRX", 10, 5, -10));
		AgentTask t = new AgentTask(MEMBER, 2L, 3L, skillId, "원래 제목", skillId == null ? "원래 지시" : null, configJson,
			scheduleJson, limits == null ? null : json.write(limits), true, Instant.now().plusSeconds(600), parentId, null, 0,
			AgentTask.CreatedBy.USER);
		when(tasks.findByIdAndMemberId(TASK_ID, MEMBER)).thenReturn(Optional.of(t));
		when(trading.limitsOf(t)).thenReturn(limits);
		when(trading.view(t)).thenReturn(view(limits, "0"));
		return t;
	}

	private static LedgerView view(Limits l, String invested) {
		BigDecimal inv = new BigDecimal(invested);
		return new LedgerView(l, BigDecimal.ZERO, BigDecimal.ZERO, inv, l == null ? BigDecimal.ZERO : l.budget().subtract(inv),
			BigDecimal.ZERO, List.of());
	}

	private static UpdateTaskRequest limitsRequest(String budget, Boolean confirm) {
		return new UpdateTaskRequest(null, null, null, null, budget == null ? null : new BigDecimal(budget), null, null, null, null,
			confirm);
	}

	@Test
	void raisingTheBudgetAlsoRaisesTheDailyBuyCapThatFollowedTheOldBudget() {
		AgentTask t = task(null, limits("5000000"), null, null);

		service.applyEdit(MEMBER, TASK_ID, limitsRequest("8000000", null), false);

		Limits saved = json.read(t.getLimitsJson(), Limits.class);
		assertThat(saved.budget()).isEqualByComparingTo("8000000");
		assertThat(saved.maxDailyBuy()).isEqualByComparingTo("8000000");
		assertThat(saved.maxPositionPct()).isEqualTo(30.0);
		assertThat(saved.currency()).isEqualTo("KRW");
	}

	@Test
	void aCustomDailyCapIsKeptWhenOnlyTheBudgetChanges() {
		Limits custom = new Limits(new BigDecimal("5000000"), "KRW", 30.0, new BigDecimal("1000000"), 15.0);
		AgentTask t = task(null, custom, null, null);

		service.applyEdit(MEMBER, TASK_ID, limitsRequest("8000000", null), false);

		assertThat(json.read(t.getLimitsJson(), Limits.class).maxDailyBuy()).isEqualByComparingTo("1000000");
	}

	@Test
	void budgetCannotGoBelowWhatIsCurrentlyInvested() {
		task(null, limits("5000000"), null, null);
		when(trading.view(any())).thenReturn(view(limits("5000000"), "3000000"));

		assertThatThrownBy(() -> service.applyEdit(MEMBER, TASK_ID, limitsRequest("2000000", null), false))
			.isInstanceOf(ApiException.class).hasMessageContaining("작게 줄일 수 없습니다");
	}

	@Test
	void invalidLimitValuesAreRejected() {
		task(null, limits("5000000"), null, null);
		assertThatThrownBy(() -> service.applyEdit(MEMBER, TASK_ID, limitsRequest("-5", null), false)).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> service.applyEdit(MEMBER, TASK_ID,
			new UpdateTaskRequest(null, null, null, null, null, 150.0, null, null, null, null), false)).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> service.applyEdit(MEMBER, TASK_ID,
			new UpdateTaskRequest(null, null, null, null, null, null, 0.0, null, null, null), false)).isInstanceOf(ApiException.class);
	}

	@Test
	void realAccountAutonomousSettingChangesNeedExplicitConfirmation() {
		AgentTask t = task(null, limits("5000000"), null, null);

		assertThatThrownBy(() -> service.applyEdit(MEMBER, TASK_ID, limitsRequest("9000000", null), true))
			.isInstanceOf(ApiException.class).extracting(e -> ((ApiException)e).getErrorCode()).isEqualTo(ErrorCode.AGENT_CONFIRM_REQUIRED);
		assertThat(json.read(t.getLimitsJson(), Limits.class).budget()).isEqualByComparingTo("5000000");

		service.applyEdit(MEMBER, TASK_ID, limitsRequest("9000000", true), true);
		assertThat(json.read(t.getLimitsJson(), Limits.class).budget()).isEqualByComparingTo("9000000");
	}

	@Test
	void titleOrInstructionOnlyEditsDoNotNeedTheRealTradingConfirmation() {
		AgentTask t = task(null, limits("5000000"), null, null);

		service.applyEdit(MEMBER, TASK_ID, new UpdateTaskRequest("새 제목", "새 지시", "REPLACE"), true);

		assertThat(t.getTitle()).isEqualTo("새 제목");
		assertThat(t.getInstruction()).isEqualTo("새 지시");
		assertThat(t.getInstructionHistory()).contains("원래 지시");
	}

	@Test
	void skillTaskConfigEditRecomputesLimitsScheduleAndTitleButKeepsOtherSettings() {
		Map<String, Object> cfg = skill.normalize(Map.of("budget", 5_000_000, "maxPositions", 4, "checkIntervalMinutes", 10));
		AgentTask t = task(skill.id(), skill.limits(cfg), json.write(cfg), null);

		service.applyEdit(MEMBER, TASK_ID, new UpdateTaskRequest(null, null, null, Map.of("budget", 7_000_000, "checkIntervalMinutes", 30),
			null, null, null, null, null, null), false);

		Map<?, ?> savedCfg = json.read(t.getConfigJson(), Map.class);
		assertThat(((Number)savedCfg.get("budget")).doubleValue()).isEqualTo(7_000_000);
		assertThat(savedCfg.get("maxPositions")).isEqualTo(4); // 보내지 않은 값은 유지
		Limits saved = json.read(t.getLimitsJson(), Limits.class);
		assertThat(saved.budget()).isEqualByComparingTo("7000000");
		assertThat(saved.maxPositionPct()).isEqualTo(25.0);
		assertThat(t.getScheduleJson()).contains("30");
		assertThat(t.getTitle()).contains("7,000,000");
	}

	@Test
	void skillTaskConfigIsValidatedBySkillRules() {
		Map<String, Object> cfg = skill.normalize(Map.of());
		task(skill.id(), skill.limits(cfg), json.write(cfg), null);

		assertThatThrownBy(() -> service.applyEdit(MEMBER, TASK_ID,
			new UpdateTaskRequest(null, null, null, Map.of("stopLossPct", 99), null, null, null, null, null, null), false))
			.isInstanceOf(ApiException.class);
	}

	@Test
	void skillTaskRejectsInstructionAndDirectLimitEdits() {
		Map<String, Object> cfg = skill.normalize(Map.of());
		task(skill.id(), skill.limits(cfg), json.write(cfg), null);

		assertThatThrownBy(() -> service.applyEdit(MEMBER, TASK_ID, new UpdateTaskRequest(null, "지시", "REPLACE"), false))
			.isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> service.applyEdit(MEMBER, TASK_ID, limitsRequest("9000000", null), false)).isInstanceOf(ApiException.class);
	}

	@Test
	void childTasksCannotChangeTheirOwnLimits() {
		task(null, limits("5000000"), null, 99L);
		assertThatThrownBy(() -> service.applyEdit(MEMBER, TASK_ID, limitsRequest("9000000", null), false))
			.isInstanceOf(ApiException.class).hasMessageContaining("상위 작업");
	}

	@Test
	void settingsCannotChangeWhileRunningButARenameCan() {
		AgentTask t = task(null, limits("5000000"), null, null);
		java.lang.reflect.Field status;
		try {
			status = AgentTask.class.getDeclaredField("status");
			status.setAccessible(true);
			status.set(t, AgentTask.Status.RUNNING);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}

		assertThatThrownBy(() -> service.applyEdit(MEMBER, TASK_ID, limitsRequest("9000000", null), false))
			.isInstanceOf(ApiException.class).extracting(e -> ((ApiException)e).getErrorCode()).isEqualTo(ErrorCode.AGENT_STATE_CONFLICT);

		service.applyEdit(MEMBER, TASK_ID, new UpdateTaskRequest("이름만", null, null), false);
		assertThat(t.getTitle()).isEqualTo("이름만");
	}

	@Test
	void finishedTasksCannotBeEdited() {
		AgentTask t = task(null, limits("5000000"), null, null);
		t.cancel("중지");
		assertThatThrownBy(() -> service.applyEdit(MEMBER, TASK_ID, new UpdateTaskRequest("x", null, null), false))
			.isInstanceOf(ApiException.class);
	}

	@Test
	void anEmptyRequestIsRejected() {
		task(null, limits("5000000"), null, null);
		assertThatThrownBy(() -> service.applyEdit(MEMBER, TASK_ID, new UpdateTaskRequest(null, null, null), false))
			.isInstanceOf(ApiException.class).hasMessageContaining("바꿀 내용이 없습니다");
	}

	@Test
	void scheduleEditOnAGenericTaskReplacesTheScheduleAndTheNextRun() {
		AgentTask t = task(null, limits("5000000"), null, null);
		Instant before = t.getNextRunAt();
		Schedule newSchedule = Schedule.marketInterval("KRX", 30, 5, -10);

		service.applyEdit(MEMBER, TASK_ID, new UpdateTaskRequest(null, null, null, null, null, null, null, null, newSchedule, null), false);

		assertThat(t.getScheduleJson()).contains("30");
		assertThat(t.getNextRunAt()).isNotNull().isNotEqualTo(before);
	}
}
