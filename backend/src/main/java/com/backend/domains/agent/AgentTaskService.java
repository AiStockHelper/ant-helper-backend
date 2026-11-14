package com.backend.domains.agent;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backend.common.exception.ApiException;
import com.backend.common.exception.ErrorCode;
import com.backend.common.properties.AppProperties;
import com.backend.common.util.Json;
import com.backend.common.util.TimeUtil;
import com.backend.domains.account.AccountService;
import com.backend.domains.account.AccountService.AccountDeletedEvent;
import com.backend.domains.agent.AgentDtos.AgentTaskDetail;
import com.backend.domains.agent.AgentDtos.AgentTaskDto;
import com.backend.domains.agent.AgentDtos.CreateTaskRequest;
import com.backend.domains.agent.AgentDtos.LedgerDto;
import com.backend.domains.agent.AgentDtos.PositionDto;
import com.backend.domains.agent.AgentDtos.RunDto;
import com.backend.domains.agent.AgentDtos.TradeDto;
import com.backend.market.TradeProfit;
import com.backend.domains.agent.AgentTradingService.LedgerView;
import com.backend.domains.agent.skill.Skill;
import com.backend.domains.agent.skill.SkillRegistry;
import com.backend.domains.chat.entity.ChatConversation;
import com.backend.domains.chat.repository.ChatConversationRepository;
import com.backend.domains.member.domain.MemberAccount;
import com.backend.domains.member.repository.MemberAccountRepository;
import com.backend.market.Currency;
import com.backend.market.TradingCalendar;
import com.fasterxml.jackson.core.type.TypeReference;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 자동 실행 작업의 생성·조회·일시정지·재개·중지. 실행 자체는 {@link AgentRunService} 가 맡는다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentTaskService {

	private static final int MAX_DEPTH = 3;
	private static final Duration MAX_AHEAD = Duration.ofDays(365);
	private static final List<AgentTask.Status> LIVE = List.of(AgentTask.Status.ACTIVE, AgentTask.Status.RUNNING,
		AgentTask.Status.PAUSED);

	/** 작업 생성에 필요한 모든 값. 검증은 {@link #create} 가 한다. */
	public record CreateSpec(
		MemberAccount account, Long conversationId, String skillId, String title, String instruction,
		Map<String, Object> config, Schedule schedule, Limits limits, boolean autonomous, Long parentTaskId,
		AgentTask.CreatedBy createdBy
	) {
	}

	private final AgentTaskRepository tasks;
	private final AgentRunRepository runs;
	private final MemberAccountRepository accounts;
	private final AccountService accountService;
	private final ChatConversationRepository conversations;
	private final SkillRegistry registry;
	private final TradingCalendar calendar;
	private final AgentTradingService trading;
	private final AppProperties props;
	private final Json json;
	private final com.backend.domains.auth.otp.StepUpService stepUp;

	// ─────────────────────────── 생성 ───────────────────────────

	@Transactional
	public AgentTask create(CreateSpec spec) {
		if (!props.getAgent().isEnabled()) {
			throw ApiException.from(ErrorCode.AGENT_DISABLED);
		}
		MemberAccount account = spec.account();
		Schedule schedule = spec.schedule();
		if (schedule == null) {
			throw ApiException.of(ErrorCode.AGENT_INVALID, "실행 일정이 필요합니다.");
		}
		schedule.validate();
		if (spec.title() == null || spec.title().isBlank()) {
			throw ApiException.of(ErrorCode.AGENT_INVALID, "작업 제목이 필요합니다.");
		}
		if (spec.skillId() == null && (spec.instruction() == null || spec.instruction().isBlank())) {
			throw ApiException.of(ErrorCode.AGENT_INVALID, "실행할 내용(instruction)이 필요합니다.");
		}
		if (spec.instruction() != null && spec.instruction().length() > 4000) {
			throw ApiException.of(ErrorCode.AGENT_INVALID, "지시 내용은 4000자 이하여야 합니다.");
		}
		long live = tasks.countByMemberIdAndStatusIn(account.getMemberId(), LIVE);
		if (live >= props.getAgent().getMaxActiveTasksPerMember()) {
			throw ApiException.of(ErrorCode.AGENT_LIMIT, "동시에 유지할 수 있는 자동 실행은 최대 "
				+ props.getAgent().getMaxActiveTasksPerMember() + "개입니다. 불필요한 작업을 중지한 뒤 다시 시도해 주세요.");
		}
		if (spec.autonomous() && !account.isPaper() && (spec.limits() == null || spec.limits().budget() == null
			|| spec.limits().budget().signum() <= 0)) {
			throw ApiException.of(ErrorCode.AGENT_INVALID, "실전 계좌에서 자율 주문을 하려면 예산(시드)이 필요합니다.");
		}

		Instant next = nextRunFor(schedule);

		AgentTask parent = spec.parentTaskId() == null ? null : tasks.findById(spec.parentTaskId()).orElse(null);
		int depth = parent == null ? 0 : parent.getDepth() + 1;
		if (depth > MAX_DEPTH) {
			throw ApiException.of(ErrorCode.AGENT_LIMIT, "자동 실행이 스스로 만든 작업은 " + MAX_DEPTH + "단계까지만 이어질 수 있습니다.");
		}
		String title = spec.title().trim();
		AgentTask saved = tasks.save(new AgentTask(account.getMemberId(), account.getId(), spec.conversationId(),
			spec.skillId(), title.length() > 200 ? title.substring(0, 200) : title,
			spec.instruction() == null ? null : spec.instruction().trim(),
			spec.config() == null ? null : json.write(spec.config()), json.write(schedule),
			spec.limits() == null ? null : json.write(spec.limits().normalized()), spec.autonomous(), next,
			spec.parentTaskId(), parent == null ? null : trading.ledgerOf(parent), depth, spec.createdBy()));
		saved.assignLedger(parent == null ? saved.getId() : trading.ledgerOf(parent));
		log.info("자동 실행 등록 task={} member={} account={} skill={} autonomous={} by={} schedule={} next={}", saved.getId(),
			account.getMemberId(), account.getId(), spec.skillId(), spec.autonomous(), spec.createdBy(), schedule.describe(), next);
		return saved;
	}

	/** REST 로 들어온 생성 요청(스킬 켜기 또는 직접 작성). */
	@Transactional
	public AgentTaskDto createFromRequest(Long memberId, CreateTaskRequest req) {
		MemberAccount account = accountService.resolve(memberId, req.accountId());
		boolean real = !account.isPaper();
		if (req.skillId() != null && !req.skillId().isBlank()) {
			Skill skill = registry.require(req.skillId());
			if (real && skill.trades()) {
				if (!Boolean.TRUE.equals(req.confirmRealTrading())) {
					throw ApiException.from(ErrorCode.AGENT_CONFIRM_REQUIRED);
				}
				stepUp.require(memberId);
			}
			Map<String, Object> cfg = skill.normalize(req.config());
			Long conversationId = newConversation(account, "🤖 " + skill.name());
			AgentTask t = create(new CreateSpec(account, conversationId, skill.id(), skill.title(cfg), null, cfg,
				skill.schedule(cfg), skill.limits(cfg), skill.trades(), null, AgentTask.CreatedBy.USER));
			return toDto(t);
		}
		boolean autonomous = Boolean.TRUE.equals(req.autonomous());
		if (autonomous && real) {
			if (!Boolean.TRUE.equals(req.confirmRealTrading())) {
				throw ApiException.from(ErrorCode.AGENT_CONFIRM_REQUIRED);
			}
			stepUp.require(memberId);
		}
		Limits limits = req.budget() == null ? null : new Limits(req.budget(), currencyOf(req.schedule()), req.maxPositionPct(),
			null, req.maxLossPct()).normalized();
		Long conversationId = newConversation(account, "🤖 " + (req.title() == null ? "자동 실행" : req.title()));
		AgentTask t = create(new CreateSpec(account, conversationId, null, req.title(), req.instruction(), null, req.schedule(),
			limits, autonomous, null, AgentTask.CreatedBy.USER));
		return toDto(t);
	}

	/** 확인 카드 승인 시 호출: 카드에 저장해 둔 내용으로 작업을 만든다. */
	@Transactional
	public AgentTask createFromApproval(MemberAccount account, Long conversationId, Map<String, Object> payload) {
		Schedule schedule = json.mapper().convertValue(payload.get("schedule"), Schedule.class);
		Limits limits = payload.get("limits") == null ? null : json.mapper().convertValue(payload.get("limits"), Limits.class);
		return create(new CreateSpec(account, conversationId, null, String.valueOf(payload.get("title")),
			String.valueOf(payload.get("instruction")), null, schedule, limits, Boolean.TRUE.equals(payload.get("autonomous")),
			null, AgentTask.CreatedBy.USER));
	}

	/** 실행 중인 AI 가 스스로 만드는 후속 작업. 자율 여부와 한도·장부는 부모를 그대로 따른다. */
	@Transactional
	public AgentTask createChild(AgentTask parent, MemberAccount account, String title, String instruction, Schedule schedule) {
		return create(new CreateSpec(account, parent.getConversationId(), null, title, instruction, null, schedule,
			json.read(parent.getLimitsJson(), Limits.class), parent.isAutonomous(), parent.getId(), AgentTask.CreatedBy.AGENT));
	}

	private static String currencyOf(Schedule s) {
		return s != null && s.marketEnum() != null && s.marketEnum().overseas() ? "USD" : "KRW";
	}

	private Long newConversation(MemberAccount account, String title) {
		ChatConversation c = conversations.save(new ChatConversation(account.getMemberId(), account.getId()));
		c.rename(title);
		return c.getId();
	}

	// ─────────────────────────── 조회 ───────────────────────────

	@Transactional(readOnly = true)
	public List<AgentTaskDto> list(Long memberId, Long accountId, String skillId, String status) {
		List<AgentTask> found = accountId == null ? tasks.findByMemberIdOrderByIdDesc(memberId)
			: tasks.findByMemberIdAndAccountIdOrderByIdDesc(memberId, accountId);
		return found.stream()
			.filter(t -> skillId == null || skillId.isBlank() || skillId.equals(t.getSkillId()))
			.filter(t -> status == null || status.isBlank() || t.getStatus().name().equalsIgnoreCase(status))
			.map(this::toDto)
			.collect(Collectors.toList());
	}

	@Transactional(readOnly = true)
	public AgentTaskDetail detail(Long memberId, Long id) {
		AgentTask t = require(memberId, id);
		List<PositionDto> positions = trading.allPositions(t).stream().map(this::positionDto).toList();
		Map<Long, TradeProfit.Realized> profits = trading.tradeProfits(t);
		List<TradeDto> trades = trading.recentTrades(t).stream().map(x -> tradeDto(x, profits.get(x.getId()))).toList();
		List<RunDto> runDtos = runs.findTop20ByTaskIdOrderByIdDesc(t.getId()).stream().map(this::runDto).toList();
		return new AgentTaskDetail(toDto(t), positions, trades, runDtos, t.getMemory(), revisionsOf(t));
	}

	public AgentTask require(Long memberId, Long id) {
		return tasks.findByIdAndMemberId(id, memberId).orElseThrow(() -> ApiException.from(ErrorCode.AGENT_TASK_NOT_FOUND));
	}

	public AgentTask requireById(Long id) {
		return tasks.findById(id).orElseThrow(() -> ApiException.from(ErrorCode.AGENT_TASK_NOT_FOUND));
	}

	// ─────────────────────────── 상태 변경 ───────────────────────────

	@Transactional
	public AgentTaskDto pause(Long memberId, Long id) {
		AgentTask t = require(memberId, id);
		if (t.getStatus() != AgentTask.Status.ACTIVE && t.getStatus() != AgentTask.Status.RUNNING) {
			throw ApiException.of(ErrorCode.AGENT_STATE_CONFLICT, "실행 중인 작업만 일시정지할 수 있습니다.");
		}
		t.pause("사용자가 일시정지했습니다.");
		log.info("자동 실행 일시정지 task={} member={}", id, memberId);
		return toDto(t);
	}

	@Transactional
	public AgentTaskDto resume(Long memberId, Long id) {
		AgentTask t = require(memberId, id);
		if (t.getStatus() != AgentTask.Status.PAUSED) {
			throw ApiException.of(ErrorCode.AGENT_STATE_CONFLICT, "일시정지된 작업만 다시 시작할 수 있습니다.");
		}
		MemberAccount account = accounts.findById(t.getAccountId())
			.orElseThrow(() -> ApiException.from(ErrorCode.MEMBER_ACCOUNT_NOT_FOUND));
		if (t.isAutonomous() && !account.isPaper()) {
			stepUp.require(memberId);
		}
		Schedule schedule = scheduleOf(t);
		Instant next = schedule.next(Instant.now(), calendar).orElseThrow(() ->
			ApiException.of(ErrorCode.AGENT_STATE_CONFLICT, "이미 지난 일회성 작업이라 다시 시작할 수 없습니다."));
		t.resume(next);
		log.info("자동 실행 재개 task={} member={} next={}", id, memberId, next);
		return toDto(t);
	}

	// ─────────────────────────── 지시(정리본) 수정 ───────────────────────────

	public static final int MAX_INSTRUCTION = 4000;
	private static final int MAX_REVISIONS = 20;

	public record Revision(String at, String by, String title, String instruction) {
	}

	/** REPLACE 면 새 내용으로 통째로 바꾸고, APPEND 면 기존 정리본 뒤에 '추가 지시'로 덧붙인 결과를 돌려준다(저장은 하지 않는다). */
	public String mergedInstruction(AgentTask t, String text, String mode) {
		return merge(t.getInstruction(), text, mode);
	}

	static String merge(String existing, String text, String mode) {
		String incoming = text == null ? "" : text.trim();
		if (incoming.isEmpty()) {
			throw ApiException.of(ErrorCode.AGENT_INVALID, "바꿀 내용이 비어 있습니다.");
		}
		String merged = "APPEND".equalsIgnoreCase(mode)
			? (existing == null ? "" : existing.trim()) + "\n\n[추가 지시 " + java.time.LocalDate.now(TimeUtil.SEOUL) + "]\n" + incoming
			: incoming;
		if (merged.length() > MAX_INSTRUCTION) {
			throw ApiException.of(ErrorCode.AGENT_INVALID, "지시 내용은 " + MAX_INSTRUCTION + "자 이하여야 합니다(현재 " + merged.length() + "자).");
		}
		return merged;
	}
	/** 수정할 수 있는 작업인지 확인한다(스킬로 만든 작업·끝난 작업은 제외). */
	public AgentTask requireRevisable(Long memberId, Long id) {
		AgentTask t = require(memberId, id);
		if (t.getSkillId() != null) {
			throw ApiException.of(ErrorCode.AGENT_INVALID, "스킬로 켠 작업은 지시문이 없습니다. 스킬 화면에서 설정을 바꿔 주세요.");
		}
		if (t.getStatus() == AgentTask.Status.CANCELED || t.getStatus() == AgentTask.Status.DONE) {
			throw ApiException.of(ErrorCode.AGENT_STATE_CONFLICT, "이미 끝났거나 중지된 작업은 수정할 수 없습니다.");
		}
		return t;
	}

	/** 지시(정리본)와 제목을 바꾸고 이전 내용을 이력에 남긴다. 승인·추가 인증은 호출한 쪽이 확인한 뒤에 부른다. */
	@Transactional
	public AgentTaskDto applyRevision(Long memberId, Long id, String newTitle, String newInstruction, String by) {
		AgentTask t = requireRevisable(memberId, id);
		if (newInstruction == null || newInstruction.isBlank() || newInstruction.length() > MAX_INSTRUCTION) {
			throw ApiException.of(ErrorCode.AGENT_INVALID, "지시 내용은 비어 있지 않고 " + MAX_INSTRUCTION + "자 이하여야 합니다.");
		}
		List<Revision> history = new java.util.ArrayList<>(json.read(t.getInstructionHistory(), new TypeReference<List<Revision>>() {}, List.of()));
		history.add(new Revision(OffsetDateTime.now(TimeUtil.SEOUL).toString(), by, t.getTitle(), t.getInstruction()));
		while (history.size() > MAX_REVISIONS) {
			history.remove(0);
		}
		t.revise(newTitle, newInstruction.trim(), json.write(history));
		log.info("자동 실행 지시 수정 task={} member={} by={} 길이={}", id, memberId, by, newInstruction.length());
		return toDto(t);
	}

	// ─────────────────────────── 설정·한도·일정 수정 ───────────────────────────

	/** 이 작업의 다음 실행 시각. 일회성은 미래(30초 이후, 1년 이내)여야 한다. */
	Instant nextRunFor(Schedule schedule) {
		Instant now = Instant.now();
		if (schedule.type() == Schedule.Type.ONCE) {
			Instant next = schedule.parseOnce();
			if (next.isBefore(now.plusSeconds(30))) {
				throw ApiException.of(ErrorCode.AGENT_INVALID, "실행 시각은 지금보다 뒤여야 합니다.");
			}
			if (next.isAfter(now.plus(MAX_AHEAD))) {
				throw ApiException.of(ErrorCode.AGENT_INVALID, "일회성 예약은 최대 1년 이내까지만 가능합니다.");
			}
			return next;
		}
		return schedule.next(now, calendar).orElseThrow(() ->
			ApiException.of(ErrorCode.AGENT_INVALID, "다음 실행 시각을 계산할 수 없습니다. 일정(요일·시장·시각)을 확인해 주세요."));
	}

	/**
	 * 수정할 수 있는 작업인지 확인한다. 이미 끝났거나 중지된 작업은 수정할 수 없고,
	 * 지금 실행 중인 작업의 설정·한도·일정은 실행이 끝나야 바꿀 수 있다(실행 결과 저장과 겹쳐 덮어쓰이는 것을 막는다).
	 */
	public AgentTask requireEditable(Long memberId, Long id, AgentDtos.UpdateTaskRequest req) {
		AgentTask t = require(memberId, id);
		if (t.getStatus() == AgentTask.Status.CANCELED || t.getStatus() == AgentTask.Status.DONE) {
			throw ApiException.of(ErrorCode.AGENT_STATE_CONFLICT, "이미 끝났거나 중지된 작업은 수정할 수 없습니다.");
		}
		if (req != null && req.changesSettings() && t.getStatus() == AgentTask.Status.RUNNING) {
			throw ApiException.of(ErrorCode.AGENT_STATE_CONFLICT, "지금 실행 중이라 설정을 바꿀 수 없습니다. 잠시 뒤에 다시 시도해 주세요.");
		}
		return t;
	}

	/**
	 * 제목·지시문과 설정·한도·일정을 한 번에 바꾼다(보낸 항목만). 인증·확인 검사는 호출한 쪽이 먼저 끝낸 뒤에 부른다.
	 * 스킬 작업은 config 만 받아 시드·일정·한도·제목을 스킬이 다시 계산한다. 장부(보유·손익)와 실행 이력은 그대로다.
	 *
	 * @param realAutonomous 실전 계좌의 자율 주문 작업인가. 이때 설정·한도·일정을 바꾸려면 confirmRealTrading 이 필요하다.
	 */
	@Transactional
	public AgentTaskDto applyEdit(Long memberId, Long id, AgentDtos.UpdateTaskRequest req, boolean realAutonomous) {
		AgentTask t = requireEditable(memberId, id, req);
		boolean hasInstruction = req.instruction() != null && !req.instruction().isBlank();
		if (!hasInstruction && req.title() == null && !req.changesSettings()) {
			throw ApiException.of(ErrorCode.AGENT_INVALID, "바꿀 내용이 없습니다.");
		}
		if (realAutonomous && req.changesSettings() && !Boolean.TRUE.equals(req.confirmRealTrading())) {
			throw ApiException.from(ErrorCode.AGENT_CONFIRM_REQUIRED);
		}

		Limits oldLimits = trading.limitsOf(t);
		Limits newLimits = oldLimits;
		String newTitle = req.title();
		String configJson = null;
		String scheduleJson = null;
		String limitsJson = null;
		Instant nextRun = null;

		if (t.getSkillId() != null) {
			if (hasInstruction) {
				throw ApiException.of(ErrorCode.AGENT_INVALID, "스킬로 만든 작업에는 지시문이 없습니다. 설정값을 바꿔 주세요.");
			}
			if (req.changesLimits() || req.schedule() != null) {
				throw ApiException.of(ErrorCode.AGENT_INVALID, "스킬 작업의 시드·한도·일정은 설정값(config)으로 바꿉니다.");
			}
			if (req.config() != null) {
				Skill skill = registry.require(t.getSkillId());
				Map<String, Object> oldConfig = json.read(t.getConfigJson(), new TypeReference<Map<String, Object>>() {}, Map.of());
				Map<String, Object> merged = new java.util.LinkedHashMap<>(oldConfig);
				merged.putAll(req.config());
				Map<String, Object> cfg = skill.normalize(merged);
				skill.validateChange(oldConfig, cfg, !trading.view(t).open().isEmpty());
				Schedule schedule = skill.schedule(cfg);
				schedule.validate();
				newLimits = skill.limits(cfg).normalized();
				newTitle = req.title() != null && !req.title().isBlank() ? req.title() : skill.title(cfg);
				configJson = json.write(cfg);
				scheduleJson = json.write(schedule);
				limitsJson = json.write(newLimits);
				nextRun = nextRunFor(schedule);
			}
		} else {
			if (req.config() != null) {
				throw ApiException.of(ErrorCode.AGENT_INVALID, "스킬이 아닌 작업에는 설정값(config)이 없습니다.");
			}
			if (req.changesLimits()) {
				if (t.getParentTaskId() != null) {
					throw ApiException.of(ErrorCode.AGENT_INVALID, "AI가 만든 하위 작업의 한도는 상위 작업에서 바꿉니다.");
				}
				if (oldLimits == null) {
					throw ApiException.of(ErrorCode.AGENT_INVALID, "이 작업은 시드(투자 한도)가 없어 한도를 바꿀 수 없습니다.");
				}
				newLimits = mergeLimits(oldLimits, req);
				limitsJson = json.write(newLimits);
			}
			if (req.schedule() != null) {
				Schedule schedule = req.schedule();
				schedule.validate();
				nextRun = nextRunFor(schedule);
				scheduleJson = json.write(schedule);
			}
		}

		if (limitsJson != null && oldLimits != null) {
			BigDecimal invested = trading.view(t).invested();
			if (newLimits.budget().compareTo(invested) < 0) {
				throw ApiException.of(ErrorCode.AGENT_INVALID, "시드는 지금 투자 중인 금액(" + Currency.valueOf(newLimits.currency()).format(invested)
					+ ")보다 작게 줄일 수 없습니다. 먼저 보유 종목을 정리해 주세요.");
			}
		}

		String instruction = null;
		String history = null;
		if (hasInstruction) {
			instruction = req.instruction().trim();
			if (instruction.length() > MAX_INSTRUCTION) {
				throw ApiException.of(ErrorCode.AGENT_INVALID, "지시 내용은 " + MAX_INSTRUCTION + "자 이하여야 합니다.");
			}
		}
		if (instruction != null) {
			String merged = "APPEND".equalsIgnoreCase(req.mode()) ? merge(t.getInstruction(), instruction, "APPEND") : instruction;
			List<Revision> revisions = new java.util.ArrayList<>(
				json.read(t.getInstructionHistory(), new TypeReference<List<Revision>>() {}, List.of()));
			revisions.add(new Revision(OffsetDateTime.now(TimeUtil.SEOUL).toString(), "USER", t.getTitle(), t.getInstruction()));
			while (revisions.size() > MAX_REVISIONS) {
				revisions.remove(0);
			}
			history = json.write(revisions);
			instruction = merged;
		}

		if (instruction != null) {
			t.revise(newTitle, instruction, history);
		} else {
			t.rename(newTitle);
		}
		t.changeSettings(configJson, scheduleJson, limitsJson);
		if (nextRun != null && t.getStatus() == AgentTask.Status.ACTIVE) {
			t.setNextRunAt(nextRun);
		}
		log.info("자동 실행 수정 task={} member={} 설정변경={} 지시변경={} 제목변경={}", id, memberId, req.changesSettings(),
			instruction != null, newTitle != null);
		return toDto(t);
	}

	/** 한도 수정값을 기존 한도에 합친다. 하루 매수 한도가 '시드와 같음'(기본)이었다면 시드를 따라 바뀐다. */
	static Limits mergeLimits(Limits old, AgentDtos.UpdateTaskRequest req) {
		BigDecimal budget = req.budget() != null ? req.budget() : old.budget();
		if (budget == null || budget.signum() <= 0) {
			throw ApiException.of(ErrorCode.AGENT_INVALID, "시드는 0보다 커야 합니다.");
		}
		Double position = req.maxPositionPct() != null ? req.maxPositionPct() : old.maxPositionPct();
		if (position != null && (position <= 0 || position > 100)) {
			throw ApiException.of(ErrorCode.AGENT_INVALID, "한 종목 비중은 0 초과 100 이하여야 합니다.");
		}
		Double loss = req.maxLossPct() != null ? req.maxLossPct() : old.maxLossPct();
		if (loss != null && (loss <= 0 || loss > 100)) {
			throw ApiException.of(ErrorCode.AGENT_INVALID, "누적 손실 한도는 0 초과 100 이하여야 합니다.");
		}
		BigDecimal daily = req.maxDailyBuy();
		if (daily == null) {
			boolean followedBudget = old.maxDailyBuy() == null || old.maxDailyBuy().compareTo(old.budget()) == 0;
			daily = followedBudget ? budget : old.maxDailyBuy();
		}
		if (daily.signum() <= 0) {
			throw ApiException.of(ErrorCode.AGENT_INVALID, "하루 매수 한도는 0보다 커야 합니다.");
		}
		return new Limits(budget, old.currency(), position, daily, loss).normalized();
	}

	public List<AgentDtos.RevisionDto> revisionsOf(AgentTask t) {
		List<Revision> history = json.read(t.getInstructionHistory(), new TypeReference<List<Revision>>() {}, List.of());
		List<AgentDtos.RevisionDto> out = new java.util.ArrayList<>();
		for (int i = history.size() - 1; i >= 0; i--) {
			Revision r = history.get(i);
			out.add(new AgentDtos.RevisionDto(r.at(), r.by(), r.title(), r.instruction()));
		}
		return out;
	}
	/** 중지. 이 작업이 관리하던 보유 종목은 계좌에 그대로 남고 더 이상 자동 관리되지 않는다. */
	@Transactional
	public AgentTaskDto cancel(Long memberId, Long id) {
		AgentTask t = require(memberId, id);
		if (t.getStatus() == AgentTask.Status.CANCELED || t.getStatus() == AgentTask.Status.DONE) {
			throw ApiException.of(ErrorCode.AGENT_STATE_CONFLICT, "이미 끝났거나 중지된 작업입니다.");
		}
		cancelInternal(t, "사용자가 중지했습니다.");
		return toDto(t);
	}

	private void cancelInternal(AgentTask t, String reason) {
		t.cancel(reason);
		boolean root = t.getLedgerId() == null || t.getLedgerId().equals(t.getId());
		if (root) {
			for (AgentTask child : tasks.findByLedgerId(trading.ledgerOf(t))) {
				if (!child.getId().equals(t.getId()) && child.getStatus() != AgentTask.Status.CANCELED
					&& child.getStatus() != AgentTask.Status.DONE) {
					child.cancel(reason);
				}
			}
			trading.releaseAll(trading.ledgerOf(t), "자동 관리 중지(" + reason + ")");
		}
		log.info("자동 실행 중지 task={} reason={}", t.getId(), reason);
	}

	@Transactional
	public int pauseAll(Long memberId) {
		int n = tasks.pauseAllForMember(memberId, "비상 정지: 사용자가 모든 자동 실행을 일시정지했습니다.");
		log.warn("자동 실행 전체 일시정지 member={} 대상={}건", memberId, n);
		return n;
	}

	@Transactional
	public void deleteAllForMember(Long memberId) {
		runs.deleteAllByMemberId(memberId);
		trading.deleteAllForMember(memberId);
		tasks.deleteAllByMemberId(memberId);
	}

	@EventListener
	@Transactional
	public void onAccountDeleted(AccountDeletedEvent event) {
		for (AgentTask t : tasks.findByMemberIdAndAccountIdOrderByIdDesc(event.memberId(), event.accountId())) {
			if (t.getStatus() != AgentTask.Status.CANCELED && t.getStatus() != AgentTask.Status.DONE) {
				cancelInternal(t, "계좌가 삭제되어 자동 실행이 중지되었습니다.");
			}
		}
	}

	// ─────────────────────────── 변환 ───────────────────────────

	public Schedule scheduleOf(AgentTask t) {
		Schedule s = json.read(t.getScheduleJson(), Schedule.class);
		if (s == null) {
			throw ApiException.of(ErrorCode.AGENT_INVALID, "저장된 일정을 읽을 수 없습니다.");
		}
		return s;
	}

	public AgentTaskDto toDto(AgentTask t) {
		MemberAccount account = accounts.findById(t.getAccountId()).orElse(null);
		Skill skill = registry.find(t.getSkillId());
		Schedule schedule = json.read(t.getScheduleJson(), Schedule.class);
		Map<String, Object> config = json.read(t.getConfigJson(), new TypeReference<Map<String, Object>>() {}, Map.of());
		Limits limits = trading.limitsOf(t);
		LedgerDto ledger = limits == null ? null : ledgerDto(trading.view(t));
		return new AgentTaskDto(t.getId(), t.getAccountId(), account == null ? null : account.getAccountType(),
			account == null ? "삭제된 계좌" : account.displayName(), t.getSkillId(), skill == null ? null : skill.name(),
			t.getTitle(), t.getInstruction(), t.getStatus().name(), t.getPausedReason(), t.isAutonomous(), schedule,
			schedule == null ? null : schedule.describe(), TimeUtil.kst(t.getNextRunAt()), TimeUtil.kst(t.getLastRunAt()),
			t.getLastResult(), t.getRunCount(), t.getFailCount(), t.getConversationId(), t.getCreatedBy().name(),
			t.getParentTaskId(), config, ledger, TimeUtil.kst(t.getCreatedAt()));
	}

	private LedgerDto ledgerDto(LedgerView v) {
		Limits l = v.limits();
		BigDecimal pct = l.budget().signum() == 0 ? BigDecimal.ZERO
			: v.totalPnl().multiply(BigDecimal.valueOf(100)).divide(l.budget(), 2, RoundingMode.HALF_UP);
		return new LedgerDto(l.currency(), l.budget(), v.available(), v.invested(), v.realized(), v.unrealized(),
			v.totalPnl(), pct.doubleValue(), v.open().size(), v.boughtToday(), l.maxPositionPct(), l.maxLossPct(), l.dailyBuyCap());
	}

	private PositionDto positionDto(AgentPosition p) {
		Currency cur = p.getMarket().currency();
		return new PositionDto(p.getId(), p.getMarket().name(), cur.name(), p.getSymbol(), p.getName(), p.getQuantity(),
			p.getAvgPrice(), p.getLastPrice(), Math.round(p.pnlPct() * 100) / 100.0, p.unrealizedPnl(), p.getRealizedPnl(),
			p.getStatus().name(), TimeUtil.kst(p.getOpenedAt()), TimeUtil.kst(p.getClosedAt()), p.getExitReason());
	}

	private TradeDto tradeDto(AgentTrade t, TradeProfit.Realized r) {
		return new TradeDto(t.getId(), t.getSide(), t.getMarket().name(), t.getMarket().currency().name(), t.getSymbol(),
			t.getName(), t.getQuantity(), t.getPrice(), t.getAmount(), t.getReason(), TimeUtil.kst(t.getCreatedAt()),
			r == null ? null : r.costPrice(), r == null ? null : r.pnl(), r == null ? null : r.pnlRate());
	}

	private RunDto runDto(AgentRun r) {
		return new RunDto(r.getId(), r.getStatus().name(), r.isManual(), TimeUtil.kst(r.getStartedAt()),
			TimeUtil.kst(r.getFinishedAt()), r.getSummary(),
			json.read(r.getEventsJson(), new TypeReference<List<String>>() {}, List.of()));
	}

	@Transactional
	public void saveMemory(Long taskId, String memory) {
		requireById(taskId).saveMemory(memory);
	}
}
