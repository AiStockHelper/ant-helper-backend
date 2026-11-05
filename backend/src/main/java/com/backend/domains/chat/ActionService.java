package com.backend.domains.chat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.backend.common.exception.ApiException;
import com.backend.common.exception.ErrorCode;
import com.backend.common.properties.AppProperties;
import com.backend.common.util.Json;
import com.backend.domains.account.AccountService;
import com.backend.domains.account.AccountService.AccountDeletedEvent;
import com.backend.domains.chat.dto.ChatDtos.Action;
import com.backend.domains.chat.dto.ChatDtos.ActionResult;
import com.backend.domains.chat.dto.ChatDtos.Detail;
import com.backend.domains.chat.entity.ChatAction;
import com.backend.domains.chat.repository.ChatActionRepository;
import com.backend.domains.chat.repository.ChatConversationRepository;
import com.backend.domains.member.domain.MemberAccount;
import com.backend.domains.agent.AgentTask;
import com.backend.domains.agent.AgentTaskService;
import com.backend.domains.agent.Limits;
import com.backend.domains.agent.Schedule;
import com.backend.domains.trade.TradePlanner;
import com.backend.domains.trade.TradePlanner.Plan;
import com.backend.market.Currency;
import com.backend.market.Market;
import com.backend.market.MarketDataService;
import com.backend.market.dto.MarketDtos.OrderOutcome;
import com.backend.market.dto.MarketDtos.OrderRequest;
import com.backend.market.TradingService;
import com.backend.market.queue.OrderSource;
import com.fasterxml.jackson.core.type.TypeReference;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 실전 계좌의 주문/예약은 반드시 이 서비스의 승인 절차를 거친다.
 * 승인 전에는 KIS 로 아무 요청도 나가지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ActionService {

	private final ChatActionRepository actions;
	private final ChatConversationRepository conversations;
	private final ChatMapper mapper;
	private final Json json;
	private final AppProperties props;
	private final AccountService accountService;
	private final TradePlanner planner;
	private final TradingService tradingService;
	private final MarketDataService marketData;
	private final AgentTaskService agentTaskService;
	private final com.backend.domains.auth.otp.StepUpService stepUp;
	private final com.backend.common.security.audit.SecurityAudit audit;
	private final TransactionTemplate tx;
	private final com.backend.common.logging.ErrorEventRecorder recorder;

	// ─────────────────────────── 생성 ───────────────────────────

	public Action createOrderAction(MemberAccount account, Long conversationId, Long messageId, Plan plan) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("market", plan.market().name());
		payload.put("productNumber", plan.symbol());
		payload.put("side", plan.side());
		payload.put("quantity", plan.quantity());
		payload.put("limitPrice", plan.limitPrice());
		String title = plan.name() + " " + plan.quantity() + "주 " + plan.sideLabel();
		String summary = plan.currency().format(plan.estimatedAmount()) + " 규모 " + plan.orderTypeLabel() + " " + plan.sideLabel()
			+ " 주문입니다. 승인하면 즉시 주문이 전송됩니다.";
		return create(account, conversationId, messageId, ChatAction.Kind.ORDER, title, summary,
			planner.details(account, plan), payload);
	}

	public Action createAgentTaskAction(MemberAccount account, Long conversationId, Long messageId, String title,
		String instruction, Schedule schedule, Limits limits) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("title", title);
		payload.put("instruction", instruction);
		payload.put("schedule", schedule);
		payload.put("limits", limits);
		payload.put("autonomous", true);
		List<Detail> details = new java.util.ArrayList<>();
		details.add(new Detail("계좌", account.displayName() + " (" + (account.isPaper() ? "모의" : "실전") + ")"));
		details.add(new Detail("실행 일정", schedule.describe()));
		details.add(new Detail("자율 주문", "허용 — 승인하면 이후에는 사람 확인 없이 AI가 한도 안에서 직접 주문합니다"));
		if (limits != null) {
			Currency cur = Currency.valueOf(limits.currency());
			details.add(new Detail("총 예산(시드)", cur.format(limits.budget())));
			details.add(new Detail("한 종목 한도", cur.format(limits.positionCap())));
			details.add(new Detail("하루 매수 한도", cur.format(limits.dailyBuyCap())));
			details.add(new Detail("누적 손실 한도", cur.format(limits.lossLimitAmount()) + " (넘으면 신규 매수 중단)"));
		}
		details.add(new Detail("지시 내용", instruction.length() > 400 ? instruction.substring(0, 400) + "…" : instruction));
		details.add(new Detail("안전장치", "1회 주문 한도 적용 · 이 작업이 산 종목만 매도 · 언제든 일시정지/중지 가능"));
		String summary = "승인하면 '" + schedule.describe() + "' 일정으로 AI가 지시를 수행합니다. 실전 계좌의 실제 자금이 사용됩니다.";
		return create(account, conversationId, messageId, ChatAction.Kind.AGENT_TASK, title, summary, details, payload);
	}
	private Action create(MemberAccount account, Long conversationId, Long messageId, ChatAction.Kind kind, String title,
		String summary, List<Detail> details, Map<String, Object> payload) {
		LocalDateTime expires = LocalDateTime.now().plusMinutes(props.getTrading().getActionTtlMinutes());
		ChatAction saved = tx.execute(s -> actions.save(new ChatAction(account.getMemberId(), conversationId, messageId,
			account.getId(), kind, title, summary, json.write(details), json.write(payload), expires)));
		log.info("확인 카드 생성 actionId={} member={} account={} kind={} title={}", saved.getId(), account.getMemberId(),
			account.getId(), kind, title);
		return mapper.action(saved, account);
	}

	// ─────────────────────────── 조회/승인/거절 ───────────────────────────

	@Transactional(readOnly = true)
	public Action get(Long memberId, Long id) {
		return mapper.action(require(memberId, id));
	}

	public Action reject(Long memberId, Long id) {
		ChatAction a = require(memberId, id);
		if (a.getStatus() != ChatAction.Status.PENDING) {
			throw ApiException.from(ErrorCode.ACTION_NOT_PENDING);
		}
		log.info("확인 카드 거절 actionId={} member={}", id, memberId);
		return tx.execute(s -> {
			ChatAction fresh = actions.findById(id).orElseThrow();
			fresh.markStatus(ChatAction.Status.REJECTED, json.write(new ActionResult(false, "사용자가 거절했습니다.", null)));
			return mapper.action(fresh);
		});
	}

	public Action approve(Long memberId, Long id) {
		ChatAction a = require(memberId, id);
		if (a.getStatus() == ChatAction.Status.PENDING && a.isExpired()) {
			tx.executeWithoutResult(s -> actions.findById(id).ifPresent(x -> x.markStatus(ChatAction.Status.EXPIRED,
				json.write(new ActionResult(false, "확인 유효시간이 지나 만료되었습니다.", null)))));
			return mapper.action(require(memberId, id));
		}
		if (a.getStatus() == ChatAction.Status.PENDING && a.getAccountId() != null
			&& (a.getKind() == ChatAction.Kind.ORDER || a.getKind() == ChatAction.Kind.AGENT_TASK)
			&& !accountService.requireOwned(memberId, a.getAccountId()).isPaper()) {
			// 실계좌 주문·자동매매 승인은 이메일 인증번호를 한 번 더 확인한 뒤에만 진행한다(승인 카드는 그대로 남는다).
			stepUp.require(memberId);
		}
		Boolean claimed = tx.execute(s -> actions.claim(id) == 1);
		if (!Boolean.TRUE.equals(claimed)) {
			throw ApiException.from(ErrorCode.ACTION_NOT_PENDING);
		}

		log.info("확인 카드 승인 처리 시작 actionId={} member={} account={} kind={}", id, memberId, a.getAccountId(), a.getKind());
		ActionResult result;
		boolean ok;
		try {
			MemberAccount account = accountService.requireOwned(memberId, a.getAccountId());
			Map<String, Object> payload = json.read(a.getPayloadJson(), new TypeReference<Map<String, Object>>() {}, Map.of());
			result = switch (a.getKind()) {
				case ORDER -> executeOrder(account, a, payload);
				case AGENT_TASK -> registerAgentTask(account, a, payload);
				case SCHEDULED_ORDER, SCHEDULED_RECOMMEND_BUY -> new ActionResult(false,
					"이 방식의 예약은 더 이상 지원하지 않습니다. 같은 내용을 다시 요청해 주세요.", null);
			};
			ok = result.success();
		} catch (ApiException e) {
			log.warn("확인 카드 {} 실행 거절 [{}] {}", id, e.getErrorCode().getCode(), e.getMessage());
			result = new ActionResult(false, e.getMessage(), null);
			ok = false;
		} catch (RuntimeException e) {
			log.error("확인 카드 {} 실행 중 오류: {}", id, e.toString(), e);
			recorder.record("ACTION", a.getAccountId(), "ACTION_ERROR", e.getMessage(), e,
				"actionId=" + id + " kind=" + a.getKind());
			result = new ActionResult(false, "처리 중 오류가 발생했습니다: " + e.getMessage(), null);
			ok = false;
		}
		if (ok && a.getAccountId() != null) {
			audit.record(a.getKind() == ChatAction.Kind.AGENT_TASK ? com.backend.common.security.audit.SecurityAudit.AGENT_STARTED
				: com.backend.common.security.audit.SecurityAudit.ORDER_APPROVED, memberId, null, null,
				"action=" + id + " account=" + a.getAccountId() + " " + a.getTitle());
		}
		final ActionResult finalResult = result;
		final ChatAction.Status status = ok ? ChatAction.Status.EXECUTED : ChatAction.Status.FAILED;
		log.info("확인 카드 처리 결과 actionId={} status={} message={}", id, status, finalResult.message());
		return tx.execute(s -> {
			ChatAction fresh = actions.findById(id).orElseThrow();
			fresh.markStatus(status, json.write(finalResult));
			return mapper.action(fresh);
		});
	}

	private ActionResult executeOrder(MemberAccount account, ChatAction action, Map<String, Object> p) {
		Market market = Market.parse(String.valueOf(p.get("market")));
		String symbol = String.valueOf(p.get("productNumber"));
		String side = String.valueOf(p.get("side"));
		long qty = ((Number)p.get("quantity")).longValue();
		BigDecimal limit = p.get("limitPrice") == null ? null : new BigDecimal(p.get("limitPrice").toString());
		String name = marketData.nameOf(market, symbol);

		// 승인 시점에 한도/장 운영을 다시 확인한다(카드를 오래 열어 둔 경우 대비).
		BigDecimal ref = limit != null ? limit : marketData.quote(account, market, symbol).price();
		Plan plan = new Plan(market, symbol, name, side, qty, limit, ref, limit == null ? "시장가" : "지정가");
		planner.enforceLimits(account, plan);
		planner.requireMarketOpen(market);

		OrderOutcome out = planner.execute(account, plan, OrderSource.chat(action.getId(), action.getConversationId()));
		if (out.pending()) {
			return new ActionResult(true, out.message(), null);
		}
		return new ActionResult(out.success(), out.success()
			? plan.name() + " " + qty + "주 " + plan.sideLabel() + " 주문이 접수되었습니다." + (out.orderNo() != null ? " (주문번호 " + out.orderNo() + ")" : "")
			: "주문이 거절되었습니다: " + out.message(), out.orderNo());
	}

	private ActionResult registerAgentTask(MemberAccount account, ChatAction a, Map<String, Object> payload) {
		AgentTask t = agentTaskService.createFromApproval(account, a.getConversationId(), payload);
		return new ActionResult(true, "자동 실행이 시작되었습니다. (작업 번호 " + t.getId() + ") 결과는 이 대화와 '내 계좌 > 자동화'에서 확인하고, 언제든 일시정지·중지할 수 있습니다.",
			String.valueOf(t.getId()));
	}
	private ChatAction require(Long memberId, Long id) {
		return actions.findByIdAndMemberId(id, memberId).orElseThrow(() -> ApiException.from(ErrorCode.ACTION_NOT_FOUND));
	}

	// ─────────────────────────── 이벤트 ───────────────────────────

	@EventListener
	@Transactional
	public void onAccountDeleted(AccountDeletedEvent event) {
		actions.expirePendingForAccount(event.accountId());
		conversations.clearAccount(event.accountId());
	}
}
