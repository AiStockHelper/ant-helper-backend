package com.backend.domains.agent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.backend.common.exception.ApiException;
import com.backend.common.exception.ErrorCode;
import com.backend.common.properties.AppProperties;
import com.backend.common.util.Json;
import com.backend.domains.member.domain.MemberAccount;
import com.backend.domains.member.repository.MemberAccountRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 자동 실행 장부를 증권사 계좌의 실제 상태와 맞춘다.
 * 한국투자증권 앱 등 이 앱 밖에서 직접 매매하면 장부(이 작업이 관리하는 종목·손익·남은 예산)가 어긋나는데,
 * 스킬이 깨어날 때만 맞추던 것을 주기적으로(그리고 사용자가 원할 때 바로) 맞춘다.
 * 바뀐 내용은 실행 기록과 작업 대화방에 남는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LedgerSyncService {

	/** 동기화 결과. tasks: 확인한 작업 수, changedTasks: 장부가 바뀐 작업 수, lines: 바뀐 내용 */
	public record Result(int tasks, int changedTasks, List<String> lines) {
	}

	private static final List<AgentTask.Status> LIVE = List.of(AgentTask.Status.ACTIVE, AgentTask.Status.RUNNING,
		AgentTask.Status.PAUSED);

	private final AgentTaskRepository tasks;
	private final AgentPositionRepository positions;
	private final MemberAccountRepository accounts;
	private final AgentTradingService trading;
	private final AgentRunRepository runs;
	private final AgentReporter reporter;
	private final AppProperties props;
	private final Json json;
	private final TransactionTemplate tx;

	/** 10분마다 열린 종목이 있는 모든 작업의 장부를 계좌와 맞춘다. */
	@Scheduled(fixedDelay = 600_000, initialDelay = 180_000)
	public void sweep() {
		if (!props.getAgent().isEnabled()) {
			return;
		}
		List<AgentTask> live = new ArrayList<>();
		for (AgentTask.Status s : LIVE) {
			live.addAll(tasks.findByStatus(s));
		}
		if (live.isEmpty()) {
			return;
		}
		Result r = sync(live, false);
		if (r.changedTasks() > 0) {
			log.info("장부 동기화: 작업 {}개 중 {}개가 계좌와 달라 맞췄습니다.", r.tasks(), r.changedTasks());
		}
	}

	/** 내 모든 작업을 지금 맞춘다. */
	public Result syncMember(Long memberId) {
		List<AgentTask> mine = tasks.findByMemberIdOrderByIdDesc(memberId).stream().filter(t -> LIVE.contains(t.getStatus())).toList();
		return sync(mine, true);
	}

	/** 한 작업(과 장부를 같이 쓰는 작업)을 지금 맞춘다. */
	public Result syncTask(Long memberId, Long taskId) {
		AgentTask t = tasks.findByIdAndMemberId(taskId, memberId).orElseThrow(() -> ApiException.from(ErrorCode.AGENT_TASK_NOT_FOUND));
		if (!LIVE.contains(t.getStatus())) {
			throw ApiException.of(ErrorCode.AGENT_STATE_CONFLICT, "이미 끝났거나 중지된 작업은 동기화할 수 없습니다.");
		}
		return sync(List.of(t), true);
	}

	private Result sync(List<AgentTask> targets, boolean manual) {
		// 장부를 같이 쓰는 작업은 한 번만, 계좌별로 묶어 계좌 조회를 한 번으로 줄인다.
		Map<Long, Map<Long, AgentTask>> byAccount = new LinkedHashMap<>();
		Set<Long> seenLedgers = new LinkedHashSet<>();
		for (AgentTask t : targets) {
			Long ledger = trading.ledgerOf(t);
			if (!seenLedgers.add(ledger) || positions.findByLedgerIdAndStatus(ledger, AgentPosition.Status.OPEN).isEmpty()) {
				continue;
			}
			byAccount.computeIfAbsent(t.getAccountId(), k -> new LinkedHashMap<>()).put(ledger, t);
		}
		int changed = 0;
		List<String> all = new ArrayList<>();
		for (Map.Entry<Long, Map<Long, AgentTask>> e : byAccount.entrySet()) {
			MemberAccount account = accounts.findById(e.getKey()).orElse(null);
			if (account == null) {
				continue;
			}
			try {
				Map<String, Long> held = trading.heldQuantities(account);
				for (AgentTask t : e.getValue().values()) {
					List<String> lines = trading.reconcile(t, account, held);
					if (!lines.isEmpty()) {
						changed++;
						all.addAll(lines);
						record(t, lines, manual);
					}
				}
			} catch (RuntimeException ex) {
				log.warn("장부 동기화 실패 account={}: {}", e.getKey(), ex.getMessage());
				if (manual) {
					throw ex;
				}
			}
		}
		return new Result(seenLedgers.size(), changed, all);
	}

	/** 바뀐 내용을 실행 기록과 작업 대화방에 남긴다. */
	private void record(AgentTask task, List<String> lines, boolean manual) {
		String body = "- " + String.join("\n- ", lines);
		Instant now = Instant.now();
		try {
			tx.executeWithoutResult(s -> runs.save(new AgentRun(task.getId(), task.getMemberId(), AgentRun.Status.DONE, manual, now, now,
				"계좌와 동기화: 이 앱 밖에서 한 매매를 장부에 반영했습니다.\n" + body, json.write(lines), null)));
			reporter.post(task, "🔄 **계좌와 동기화** — " + task.getTitle(), "한국투자증권 앱 등에서 직접 매매한 내역을 반영했어요.\n\n" + body, List.of());
		} catch (RuntimeException e) {
			log.warn("동기화 기록 실패 task={}: {}", task.getId(), e.getMessage());
		}
	}
}
