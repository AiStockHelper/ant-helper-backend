package com.backend.domains.agent;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import com.backend.domains.agent.skill.Skill;
import com.backend.order.kis.enums.AccountType;

/** 자동 실행 / 스킬 API 의 요청·응답 타입. 필드명은 프론트엔드와의 계약이다. */
public final class AgentDtos {

	private AgentDtos() {
	}

	public record LedgerDto(
		String currency, BigDecimal budget, BigDecimal available, BigDecimal invested, BigDecimal realizedPnl,
		BigDecimal unrealizedPnl, BigDecimal totalPnl, double totalPnlPct, int openPositions, BigDecimal boughtToday,
		Double maxPositionPct, Double maxLossPct, BigDecimal maxDailyBuy
	) {
	}

	public record AgentTaskDto(
		Long id, Long accountId, AccountType accountType, String accountLabel, String skillId, String skillName,
		String title, String instruction, String status, String pausedReason, boolean autonomous,
		Schedule schedule, String scheduleLabel, OffsetDateTime nextRunAt, OffsetDateTime lastRunAt, String lastResult,
		int runCount, int failCount, Long conversationId, String createdBy, Long parentTaskId,
		Map<String, Object> config, LedgerDto ledger, OffsetDateTime createdAt
	) {
	}

	public record PositionDto(
		Long id, String market, String currency, String symbol, String name, long quantity, BigDecimal avgPrice,
		BigDecimal lastPrice, double pnlPct, BigDecimal unrealizedPnl, BigDecimal realizedPnl, String status,
		OffsetDateTime openedAt, OffsetDateTime closedAt, String exitReason
	) {
	}

	public record TradeDto(
		Long id, String side, String market, String currency, String symbol, String name, long quantity,
		BigDecimal price, BigDecimal amount, String reason, OffsetDateTime createdAt,
		/** 매도일 때 그때까지의 평균 매입단가, 실현손익(수수료·세금 제외), 손익률(%). 원가를 알 수 없거나 매수면 null */
		BigDecimal costPrice, BigDecimal realizedPnl, Double realizedPnlPct
	) {
	}

	public record RunDto(
		Long id, String status, boolean manual, OffsetDateTime startedAt, OffsetDateTime finishedAt, String summary,
		List<String> events
	) {
	}

	public record AgentTaskDetail(
		AgentTaskDto task, List<PositionDto> positions, List<TradeDto> trades, List<RunDto> runs, String memory,
		List<RevisionDto> revisions
	) {
	}

	/** 지시(정리본)를 고치기 전의 모습 */
	public record RevisionDto(String at, String by, String title, String instruction) {
	}

	/**
	 * 작업 수정 요청. 보낸 항목만 바꾼다(null 은 그대로).
	 * 스킬로 만든 작업은 config(설정값)로만 바꾼다: 시드·일정·한도가 설정값에서 다시 계산된다.
	 * 일반 작업은 title·instruction(+mode)과 한도(budget 등)·schedule 을 바꿀 수 있다.
	 * 실전 계좌의 자율 주문 작업에서 설정·한도·일정을 바꿀 땐 confirmRealTrading=true 가 필요하다.
	 */
	public record UpdateTaskRequest(
		String title, String instruction, String mode,
		Map<String, Object> config,
		BigDecimal budget, Double maxPositionPct, Double maxLossPct, BigDecimal maxDailyBuy,
		Schedule schedule,
		Boolean confirmRealTrading
	) {
		/** 예전 클라이언트(제목·지시문만 보내는) 호환용 */
		public UpdateTaskRequest(String title, String instruction, String mode) {
			this(title, instruction, mode, null, null, null, null, null, null, null);
		}

		public boolean changesLimits() {
			return budget != null || maxPositionPct != null || maxLossPct != null || maxDailyBuy != null;
		}

		/** 설정·한도·일정 중 하나라도 바꾸는가(제목·지시문만 바꾸는 요청은 false) */
		public boolean changesSettings() {
			return config != null || changesLimits() || schedule != null;
		}
	}

	public record CreateTaskRequest(
		Long accountId, String skillId, String title, String instruction, Map<String, Object> config,
		Schedule schedule, Boolean autonomous, BigDecimal budget, Double maxPositionPct, Double maxLossPct,
		Boolean confirmRealTrading
	) {
	}

	public record SkillDto(
		String id, String name, String summary, String detail, String category, boolean trades,
		List<Skill.Param> params
	) {
	}
}
