package com.backend.domains.schedule;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.backend.common.exception.ApiException;
import com.backend.common.exception.ErrorCode;
import com.backend.common.util.Json;
import com.backend.common.util.TimeUtil;
import com.backend.domains.account.AccountService;
import com.backend.domains.account.AccountService.AccountDeletedEvent;
import com.backend.domains.chat.ChatMapper;
import com.backend.domains.chat.dto.ChatDtos.Card;
import com.backend.domains.chat.dto.ChatDtos.ScheduledTaskDto;
import com.backend.domains.chat.entity.ChatConversation;
import com.backend.domains.chat.entity.ChatMessage;
import com.backend.domains.chat.repository.ChatConversationRepository;
import com.backend.domains.chat.repository.ChatMessageRepository;
import com.backend.domains.member.domain.MemberAccount;
import com.backend.domains.member.repository.MemberAccountRepository;
import com.backend.domains.trade.TradePlanner;
import com.backend.domains.trade.TradePlanner.Plan;
import com.backend.market.Market;
import com.backend.market.PredictionService;
import com.backend.market.dto.MarketDtos.OrderOutcome;
import com.backend.market.dto.MarketDtos.Recommendation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduleService {

	/** 예약 시각을 이만큼 넘겨서야 서버가 일어났다면 낡은 지시이므로 실행하지 않는다. */
	private static final Duration MAX_LATENESS = Duration.ofMinutes(30);
	private static final Duration MAX_AHEAD = Duration.ofDays(60);
	private static final DateTimeFormatter HUMAN = DateTimeFormatter.ofPattern("M월 d일 HH:mm");

	private final ScheduledTaskRepository tasks;
	private final MemberAccountRepository accounts;
	private final AccountService accountService;
	private final ChatMapper mapper;
	private final Json json;
	private final TradePlanner planner;
	private final PredictionService predictionService;
	private final ChatMessageRepository messages;
	private final ChatConversationRepository conversations;
	private final TransactionTemplate tx;
	private final com.backend.common.logging.ErrorEventRecorder recorder;

	// ─────────────────────────── 조회/생성/취소 ───────────────────────────

	@Transactional(readOnly = true)
	public List<ScheduledTaskDto> list(Long memberId, Long accountId, String status) {
		List<ScheduledTask> found = accountId == null
			? tasks.findByMemberIdOrderByRunAtDesc(memberId)
			: tasks.findByMemberIdAndAccountIdOrderByRunAtDesc(memberId, accountId);
		return found.stream()
			.filter(t -> status == null || status.isBlank() || t.getStatus().name().equalsIgnoreCase(status))
			.map(mapper::task)
			.collect(Collectors.toList());
	}

	@Transactional
	public ScheduledTask create(MemberAccount account, Long conversationId, ScheduledTask.Kind kind, String title,
		Instant runAt, Map<String, Object> params) {
		ScheduledTask saved = tasks.save(new ScheduledTask(account.getMemberId(), account.getId(), conversationId, kind,
			title, runAt, json.write(params)));
		log.info("예약 등록 taskId={} member={} account={} kind={} runAt={} title={}", saved.getId(), account.getMemberId(),
			account.getId(), kind, runAt, title);
		return saved;
	}

	@Transactional
	public ScheduledTaskDto cancel(Long memberId, Long id) {
		ScheduledTask t = tasks.findByIdAndMemberId(id, memberId)
			.orElseThrow(() -> ApiException.from(ErrorCode.SCHEDULE_NOT_FOUND));
		if (t.getStatus() != ScheduledTask.Status.SCHEDULED) {
			throw ApiException.from(ErrorCode.SCHEDULE_NOT_CANCELABLE);
		}
		t.cancel();
		log.info("예약 취소 taskId={} member={}", id, memberId);
		return mapper.task(t);
	}

	/**
	 * 도구가 넘긴 실행 시각 문자열을 해석한다.
	 * 오프셋이 있으면 그대로, 없으면 해당 시장의 현지 시간으로 본다.
	 */
	public Instant parseRunAt(String text, Market market) {
		if (text == null || text.isBlank()) {
			throw ApiException.of(ErrorCode.ORDER_INVALID, "실행 시각이 필요합니다.");
		}
		String t = text.trim();
		Instant at;
		try {
			at = OffsetDateTime.parse(t).toInstant();
		} catch (DateTimeParseException e) {
			try {
				ZoneId zone = market == Market.KRX ? TimeUtil.SEOUL : TimeUtil.NEW_YORK;
				at = LocalDateTime.parse(t.replace(' ', 'T')).atZone(zone).toInstant();
			} catch (DateTimeParseException e2) {
				throw ApiException.of(ErrorCode.ORDER_INVALID,
					"실행 시각 형식이 올바르지 않습니다. 예: 2026-10-25T09:00 (" + text + ")");
			}
		}
		Instant now = Instant.now();
		if (at.isBefore(now.plusSeconds(30))) {
			throw ApiException.of(ErrorCode.ORDER_INVALID, "실행 시각은 지금보다 뒤여야 합니다.");
		}
		if (at.isAfter(now.plus(MAX_AHEAD))) {
			throw ApiException.of(ErrorCode.ORDER_INVALID, "예약은 최대 60일 이내까지만 가능합니다.");
		}
		java.time.DayOfWeek day = at.atZone(market.zone()).getDayOfWeek();
		if (day == java.time.DayOfWeek.SATURDAY || day == java.time.DayOfWeek.SUNDAY) {
			String name = day == java.time.DayOfWeek.SATURDAY ? "토요일" : "일요일";
			throw ApiException.of(ErrorCode.ORDER_INVALID, "요청한 시각은 " + (market == Market.KRX ? "한국" : "미국") + " 시장 기준 "
				+ name + "이라 장이 열리지 않습니다. 다음 영업일(월요일 등)로 다시 요청해 주세요.");
		}
		return at;
	}

	public String humanTime(Instant at, Market market) {
		ZoneId zone = market == Market.KRX ? TimeUtil.SEOUL : TimeUtil.NEW_YORK;
		String label = market == Market.KRX ? "KST" : "뉴욕 현지";
		String kst = at.atZone(TimeUtil.SEOUL).format(HUMAN);
		return market == Market.KRX ? kst + " (KST)"
			: at.atZone(zone).format(HUMAN) + " (" + label + ", 한국시간 " + kst + ")";
	}

	// ─────────────────────────── 이벤트 ───────────────────────────

	@EventListener
	@Transactional
	public void onAccountDeleted(AccountDeletedEvent event) {
		tasks.cancelScheduledForAccount(event.accountId(), "계좌가 삭제되어 예약이 취소되었습니다.");
	}

	// ─────────────────────────── 실행 ───────────────────────────

	/** 스케줄러가 호출한다. 한 번만 실행되도록 선점한 뒤 처리한다. */
	public void execute(Long taskId) {
		Boolean claimed = tx.execute(s -> tasks.claim(taskId) == 1);
		if (!Boolean.TRUE.equals(claimed)) {
			return;
		}
		ScheduledTask task = tasks.findById(taskId).orElse(null);
		if (task == null) {
			return;
		}
		org.slf4j.MDC.put("memberId", String.valueOf(task.getMemberId()));
		org.slf4j.MDC.put("reqId", "task-" + taskId);
		try {
			runTask(taskId, task);
		} finally {
			org.slf4j.MDC.remove("memberId");
			org.slf4j.MDC.remove("reqId");
		}
	}

	private void runTask(Long taskId, ScheduledTask task) {
		log.info("예약 실행 시작 taskId={} account={} kind={} runAt={}", taskId, task.getAccountId(), task.getKind(),
			task.getRunAt());
		ScheduledTask.Status finalStatus;
		String message;
		List<OrderOutcome> outcomes = new ArrayList<>();
		try {
			if (Duration.between(task.getRunAt(), Instant.now()).compareTo(MAX_LATENESS) > 0) {
				finalStatus = ScheduledTask.Status.FAILED;
				message = "예약 시각(" + task.getRunAt().atZone(TimeUtil.SEOUL).format(HUMAN)
					+ ")보다 30분 넘게 늦어져 안전을 위해 실행하지 않았습니다.";
			} else {
				MemberAccount account = accounts.findById(task.getAccountId())
					.orElseThrow(() -> ApiException.from(ErrorCode.MEMBER_ACCOUNT_NOT_FOUND));
				Map<String, Object> params = json.read(task.getParamsJson(),
					new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}, Map.of());
				String note = task.getKind() == ScheduledTask.Kind.ORDER
					? runOrder(account, params, outcomes)
					: runRecommendBuy(account, params, outcomes);
				boolean anyFail = outcomes.stream().anyMatch(o -> !o.success());
				boolean anyOk = outcomes.stream().anyMatch(OrderOutcome::success);
				finalStatus = (anyFail && !anyOk) ? ScheduledTask.Status.FAILED : ScheduledTask.Status.DONE;
				message = note;
			}
		} catch (RuntimeException e) {
			log.error("예약 작업 {} 실행 실패 account={}: {}", taskId, task.getAccountId(), e.toString(), e);
			finalStatus = ScheduledTask.Status.FAILED;
			message = e instanceof ApiException ? e.getMessage() : "예약 실행 중 오류가 발생했습니다: " + e.getMessage();
		}
		final ScheduledTask.Status status = finalStatus;
		final String text = message;
		tx.executeWithoutResult(s -> tasks.findById(taskId).ifPresent(t -> t.finish(status, text)));
		if (status == ScheduledTask.Status.FAILED) {
			log.warn("예약 실행 실패 taskId={} account={} kind={}: {}", taskId, task.getAccountId(), task.getKind(), text);
			recorder.record("SCHEDULER", task.getAccountId(), "SCHEDULE_FAILED", text, null,
				"taskId=" + taskId + " kind=" + task.getKind() + " title=" + task.getTitle());
		} else {
			log.info("예약 실행 완료 taskId={} account={} status={}: {}", taskId, task.getAccountId(), status, text);
		}
		postToConversation(task, status, text, outcomes);
	}

	private String runOrder(MemberAccount account, Map<String, Object> p, List<OrderOutcome> outcomes) {
		Plan plan = planner.plan(account, str(p, "productNumber"), str(p, "market"), str(p, "side"),
			lng(p, "quantity"), dec(p, "amount"), dec(p, "price"));
		planner.enforceLimits(account, plan);
		planner.requireMarketOpen(plan.market());
		OrderOutcome out = planner.execute(account, plan);
		outcomes.add(out);
		return out.success()
			? plan.name() + " " + plan.quantity() + "주 " + plan.sideLabel() + " 주문이 접수되었습니다."
			: plan.name() + " 주문이 실패했습니다: " + out.message();
	}

	private String runRecommendBuy(MemberAccount account, Map<String, Object> p, List<OrderOutcome> outcomes) {
		String group = "US".equalsIgnoreCase(str(p, "market")) ? "US" : "KRX";
		int count = Math.max(1, Math.min(lngOr(p, "count", 3).intValue(), 10));
		BigDecimal budget = dec(p, "budget");
		if (budget == null || budget.signum() <= 0) {
			throw ApiException.of(ErrorCode.ORDER_INVALID, "예산이 올바르지 않습니다.");
		}
		List<Recommendation> picks = predictionService.recommend(account, group, count * 2).stream()
			.filter(r -> "UP".equalsIgnoreCase(r.direction()) && r.predictedChangeRate() > 0)
			.limit(count)
			.toList();
		if (picks.isEmpty()) {
			return "상승이 예측되는 추천 종목이 없어 이번에는 주문하지 않았습니다.";
		}
		planner.requireMarketOpen(picks.get(0).market());
		BigDecimal each = budget.divide(BigDecimal.valueOf(picks.size()), 2, java.math.RoundingMode.DOWN);
		StringBuilder sb = new StringBuilder("추천 종목 ").append(picks.size()).append("개에 종목당 ")
			.append(picks.get(0).currency().format(each)).append(" 한도로 매수를 시도했습니다.");
		for (Recommendation r : picks) {
			try {
				Plan plan = planner.plan(account, r.productNumber(), r.market().name(), "BUY", null, each, null);
				planner.enforceLimits(account, plan);
				outcomes.add(planner.execute(account, plan));
			} catch (ApiException e) {
				outcomes.add(new OrderOutcome(false, e.getMessage(), null, "BUY", r.productNumber(), r.name(), r.market(),
					r.currency(), 0, null));
			}
		}
		long ok = outcomes.stream().filter(OrderOutcome::success).count();
		sb.append(" 접수 ").append(ok).append("건, 실패 ").append(outcomes.size() - ok).append("건.");
		return sb.toString();
	}

	private void postToConversation(ScheduledTask task, ScheduledTask.Status status, String text, List<OrderOutcome> outcomes) {
		if (task.getConversationId() == null) {
			return;
		}
		try {
			tx.executeWithoutResult(s -> {
				ChatConversation conv = conversations.findById(task.getConversationId()).orElse(null);
				if (conv == null) {
					return;
				}
				List<Card> cards = outcomes.stream().map(o -> new Card("order_result", orderResultCard(o))).toList();
				ChatMessage m = ChatMessage.assistantPlaceholder(conv.getId(), task.getMemberId());
				String body = "⏰ **예약 실행 결과** — " + task.getTitle() + "\n\n" + text;
				m.finish(body, ChatMessage.Status.COMPLETE, null, json.write(cards), "scheduler");
				messages.save(m);
				conv.updatePreview(text);
				conv.touch();
			});
		} catch (RuntimeException e) {
			log.warn("예약 결과를 대화에 남기지 못했습니다: {}", e.getMessage());
		}
	}

	public static Map<String, Object> orderResultCard(OrderOutcome o) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("success", o.success());
		m.put("message", o.message());
		m.put("orderNo", o.orderNo());
		m.put("side", o.side());
		m.put("productNumber", o.productNumber());
		m.put("name", o.name());
		m.put("market", o.market());
		m.put("currency", o.currency());
		m.put("quantity", o.quantity());
		m.put("price", o.price());
		return m;
	}

	private static String str(Map<String, Object> p, String k) {
		Object v = p.get(k);
		return v == null ? null : v.toString();
	}

	private static Long lng(Map<String, Object> p, String k) {
		Object v = p.get(k);
		if (v == null) {
			return null;
		}
		return v instanceof Number n ? n.longValue() : Long.valueOf(v.toString());
	}

	private static Long lngOr(Map<String, Object> p, String k, long d) {
		Long v = lng(p, k);
		return v == null ? d : v;
	}

	private static BigDecimal dec(Map<String, Object> p, String k) {
		Object v = p.get(k);
		return v == null ? null : new BigDecimal(v.toString());
	}
}
