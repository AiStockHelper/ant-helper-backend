package com.backend.domains.agent;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.backend.common.exception.ApiException;
import com.backend.common.exception.ErrorCode;
import com.backend.common.util.Json;
import com.backend.domains.member.domain.MemberAccount;
import com.backend.domains.trade.TradePlanner;
import com.backend.market.queue.OrderSource;
import com.backend.domains.trade.TradePlanner.Plan;
import com.backend.market.Currency;
import com.backend.market.Market;
import com.backend.market.TradingService;
import com.backend.market.dto.MarketDtos.AccountSummary;
import com.backend.market.dto.MarketDtos.Holding;
import com.backend.market.dto.MarketDtos.OrderOutcome;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 사람 확인 없이 나가는 주문(자율 주문)의 관문.
 * 모든 자율 주문은 여기서 (1) 시드/종목 비중/일일 매수/누적 손실 한도를 검사하고 (2) 주문을 내고 (3) 장부에 기록한다.
 * 한도는 코드가 강제하므로 AI 가 프롬프트로 우회할 수 없다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentTradingService {

	public record LedgerView(
		Limits limits, BigDecimal realized, BigDecimal unrealized, BigDecimal invested, BigDecimal available,
		BigDecimal boughtToday, List<AgentPosition> open
	) {
		public BigDecimal totalPnl() {
			return realized.add(unrealized);
		}
	}

	private final AgentTaskRepository tasks;
	private final AgentPositionRepository positions;
	private final AgentTradeRepository trades;
	private final TradePlanner planner;
	private final TradingService tradingService;
	private final Json json;
	private final TransactionTemplate tx;

	private final Map<Long, ReentrantLock> locks = new ConcurrentHashMap<>();

	// ─────────────────────────── 조회 ───────────────────────────

	public Limits limitsOf(AgentTask task) {
		Limits l = json.read(task.getLimitsJson(), Limits.class);
		return l == null || l.budget() == null ? null : l.normalized();
	}

	public Long ledgerOf(AgentTask task) {
		return task.getLedgerId() != null ? task.getLedgerId() : task.getId();
	}

	public LedgerView view(AgentTask task) {
		Limits lim = limitsOf(task);
		Long ledger = ledgerOf(task);
		List<AgentPosition> all = positions.findByLedgerId(ledger);
		List<AgentPosition> open = all.stream().filter(p -> p.getStatus() == AgentPosition.Status.OPEN).toList();
		BigDecimal realized = all.stream().map(AgentPosition::getRealizedPnl).reduce(BigDecimal.ZERO, BigDecimal::add);
		BigDecimal unrealized = open.stream().map(AgentPosition::unrealizedPnl).reduce(BigDecimal.ZERO, BigDecimal::add);
		BigDecimal invested = open.stream().map(AgentPosition::cost).reduce(BigDecimal.ZERO, BigDecimal::add);
		BigDecimal budget = lim == null ? BigDecimal.ZERO : lim.budget();
		BigDecimal available = budget.add(realized).subtract(invested);
		Instant since = open.isEmpty() ? startOfToday(Market.KRX) : startOfToday(open.get(0).getMarket());
		BigDecimal bought = trades.boughtSince(ledger, since);
		return new LedgerView(lim, realized, unrealized, invested, available.max(BigDecimal.ZERO), bought, open);
	}

	public List<AgentPosition> allPositions(AgentTask task) {
		return positions.findTop60ByLedgerIdOrderByIdDesc(ledgerOf(task));
	}

	public List<AgentTrade> recentTrades(AgentTask task) {
		return trades.findTop40ByLedgerIdOrderByIdDesc(ledgerOf(task));
	}

	private static Instant startOfToday(Market m) {
		return LocalDate.now(m.zone()).atStartOfDay(m.zone()).toInstant();
	}

	// ─────────────────────────── 주문 ───────────────────────────

	public OrderOutcome buy(AgentTask task, MemberAccount account, Plan plan, String reason) {
		Limits lim = limitsOf(task);
		if (lim == null) {
			requirePaper(account);
			return planner.execute(account, plan, OrderSource.agent(task.getId(), task.getConversationId(), reason));
		}
		ReentrantLock lock = lock(ledgerOf(task));
		lock.lock();
		try {
			if (!plan.currency().name().equals(lim.currency())) {
				throw ApiException.of(ErrorCode.ORDER_INVALID, "이 작업의 예산 통화(" + lim.currency() + ")와 다른 통화("
					+ plan.currency().name() + ") 종목은 매수할 수 없습니다.");
			}
			LedgerView v = view(task);
			Currency cur = plan.currency();
			if (v.totalPnl().add(lim.lossLimitAmount()).signum() <= 0) {
				throw ApiException.of(ErrorCode.ORDER_INVALID, "누적 손실이 한도(" + cur.format(lim.lossLimitAmount())
					+ ")에 도달해 신규 매수가 중단되었습니다.");
			}
			BigDecimal cost = plan.estimatedAmount();
			if (cost.compareTo(v.available()) > 0) {
				throw ApiException.of(ErrorCode.ORDER_INVALID, "남은 예산(" + cur.format(v.available()) + ")이 부족합니다. 필요 금액 "
					+ cur.format(cost));
			}
			BigDecimal existing = v.open().stream()
				.filter(p -> p.getMarket() == plan.market() && p.getSymbol().equalsIgnoreCase(plan.symbol()))
				.map(AgentPosition::cost).reduce(BigDecimal.ZERO, BigDecimal::add);
			if (existing.add(cost).compareTo(lim.positionCap()) > 0) {
				throw ApiException.of(ErrorCode.ORDER_INVALID, plan.name() + " 한 종목 한도(" + cur.format(lim.positionCap())
					+ ")를 넘습니다.");
			}
			BigDecimal boughtToday = trades.boughtSince(ledgerOf(task), startOfToday(plan.market()));
			if (boughtToday.add(cost).compareTo(lim.dailyBuyCap()) > 0) {
				throw ApiException.of(ErrorCode.ORDER_INVALID, "오늘 매수 한도(" + cur.format(lim.dailyBuyCap()) + ")를 넘습니다.");
			}
			planner.enforceLimits(account, plan);
			planner.requireMarketOpen(plan.market());

			OrderOutcome out = planner.execute(account, plan, OrderSource.agent(task.getId(), task.getConversationId(), reason));
			if (out.success()) {
				BigDecimal price = priceOf(plan);
				tx.executeWithoutResult(s -> {
					AgentPosition pos = positions.findFirstByLedgerIdAndMarketAndSymbolAndStatus(ledgerOf(task), plan.market(),
						plan.symbol(), AgentPosition.Status.OPEN).orElse(null);
					if (pos == null) {
						positions.save(new AgentPosition(ledgerOf(task), task.getMemberId(), task.getAccountId(), plan.market(),
							plan.symbol(), plan.name(), plan.quantity(), price));
					} else {
						pos.addBuy(plan.quantity(), price);
					}
					trades.save(new AgentTrade(ledgerOf(task), task.getId(), task.getMemberId(), "BUY", plan.market(),
						plan.symbol(), plan.name(), plan.quantity(), price, out.orderNo(), reason));
				});
				log.info("자율 매수 task={} ledger={} {} {}주 @{}", task.getId(), ledgerOf(task), plan.symbol(), plan.quantity(), price);
			}
			return out;
		} finally {
			lock.unlock();
		}
	}

	public OrderOutcome sell(AgentTask task, MemberAccount account, Plan plan, String reason) {
		Limits lim = limitsOf(task);
		if (lim == null) {
			requirePaper(account);
			return planner.execute(account, plan, OrderSource.agent(task.getId(), task.getConversationId(), reason));
		}
		ReentrantLock lock = lock(ledgerOf(task));
		lock.lock();
		try {
			AgentPosition pos = positions.findFirstByLedgerIdAndMarketAndSymbolAndStatus(ledgerOf(task), plan.market(),
				plan.symbol(), AgentPosition.Status.OPEN).orElseThrow(() -> ApiException.of(ErrorCode.ORDER_INVALID,
				plan.name() + "은(는) 이 자동 실행이 매수한 종목이 아니라서 매도할 수 없습니다."));
			if (plan.quantity() > pos.getQuantity()) {
				throw ApiException.of(ErrorCode.ORDER_INVALID, plan.name() + " 보유 수량(" + pos.getQuantity()
					+ "주)보다 많이 매도할 수 없습니다.");
			}
			planner.requireMarketOpen(plan.market());
			OrderOutcome out = planner.execute(account, plan, OrderSource.agent(task.getId(), task.getConversationId(), reason));
			if (out.success()) {
				BigDecimal price = priceOf(plan);
				Long posId = pos.getId();
				tx.executeWithoutResult(s -> {
					AgentPosition fresh = positions.findById(posId).orElseThrow();
					fresh.applySell(plan.quantity(), price, reason);
					trades.save(new AgentTrade(ledgerOf(task), task.getId(), task.getMemberId(), "SELL", plan.market(),
						plan.symbol(), plan.name(), plan.quantity(), price, out.orderNo(), reason));
				});
				log.info("자율 매도 task={} ledger={} {} {}주 @{} ({})", task.getId(), ledgerOf(task), plan.symbol(),
					plan.quantity(), price, reason);
			}
			return out;
		} finally {
			lock.unlock();
		}
	}

	private static BigDecimal priceOf(Plan plan) {
		return plan.limitPrice() != null ? plan.limitPrice() : plan.refPrice();
	}

	private static void requirePaper(MemberAccount account) {
		if (!account.isPaper()) {
			throw ApiException.of(ErrorCode.ORDER_INVALID,
				"예산(시드)이 설정되지 않은 자동 실행은 실전 계좌에서 주문할 수 없습니다.");
		}
	}

	private ReentrantLock lock(Long ledgerId) {
		return locks.computeIfAbsent(ledgerId, k -> new ReentrantLock());
	}

	// ─────────────────────────── 장부 유지 ───────────────────────────

	public void mark(Long positionId, BigDecimal price) {
		tx.executeWithoutResult(s -> positions.findById(positionId).ifPresent(p -> p.mark(price)));
	}

	/** 계좌의 실제 보유와 장부를 맞춘다(수동 매도·체결 실패 등으로 어긋난 경우). 바뀐 내용을 문장으로 돌려준다. */
	public List<String> reconcile(AgentTask task, MemberAccount account) {
		List<AgentPosition> open = positions.findByLedgerIdAndStatus(ledgerOf(task), AgentPosition.Status.OPEN);
		if (open.isEmpty()) {
			return List.of();
		}
		AccountSummary summary = tradingService.summary(account);
		Map<String, Long> held = new HashMap<>();
		for (Holding h : summary.holdings()) {
			held.merge("KRX:" + h.productNumber(), h.quantity(), Long::sum);
		}
		if (summary.overseas() != null) {
			for (Holding h : summary.overseas().holdings()) {
				held.merge("US:" + h.productNumber(), h.quantity(), Long::sum);
			}
		}
		List<String> changes = new ArrayList<>();
		tx.executeWithoutResult(s -> {
			for (AgentPosition p : open) {
				AgentPosition fresh = positions.findById(p.getId()).orElse(null);
				if (fresh == null || fresh.getStatus() != AgentPosition.Status.OPEN) {
					continue;
				}
				String key = (fresh.getMarket() == Market.KRX ? "KRX:" : "US:") + fresh.getSymbol();
				long actual = held.getOrDefault(key, 0L);
				if (actual <= 0) {
					fresh.release("계좌에 보유 수량이 없어 장부에서 제외");
					changes.add(fresh.getName() + ": 계좌에 보유 수량이 없어 관리 대상에서 제외했습니다.");
				} else if (actual < fresh.getQuantity()) {
					changes.add(fresh.getName() + ": 보유 수량이 " + fresh.getQuantity() + "주에서 " + actual + "주로 줄어 장부를 맞췄습니다.");
					fresh.shrinkTo(actual, "계좌 보유 수량에 맞춤");
				}
			}
		});
		return changes;
	}

	/** 장부의 모든 열린 종목을 매도한다. 각 결과를 events 에 남긴다. */
	public List<OrderOutcome> liquidate(AgentTask task, MemberAccount account, List<String> events) {
		List<OrderOutcome> outcomes = new ArrayList<>();
		for (AgentPosition p : positions.findByLedgerIdAndStatus(ledgerOf(task), AgentPosition.Status.OPEN)) {
			try {
				Plan plan = planner.plan(account, p.getSymbol(), p.getMarket().name(), "SELL", p.getQuantity(), null, null);
				OrderOutcome out = sell(task, account, plan, "전량 정리");
				outcomes.add(out);
				events.add(out.success() ? p.getName() + " " + p.getQuantity() + "주 매도 접수" : p.getName() + " 매도 실패: " + out.message());
			} catch (ApiException e) {
				events.add(p.getName() + " 매도하지 못했습니다: " + e.getMessage());
			}
		}
		return outcomes;
	}

	public void releaseAll(Long ledgerId, String reason) {
		tx.executeWithoutResult(s -> positions.findByLedgerIdAndStatus(ledgerId, AgentPosition.Status.OPEN)
			.forEach(p -> p.release(reason)));
	}

	public void deleteAllForMember(Long memberId) {
		trades.deleteAllByMemberId(memberId);
		positions.deleteAllByMemberId(memberId);
	}

	/** AI 프롬프트와 화면에 보여줄 장부 요약 */
	public String summaryText(AgentTask task) {
		LedgerView v = view(task);
		if (v.limits() == null) {
			return "예산 한도 없음 (모의 계좌에서만 주문 가능)";
		}
		Currency cur = Currency.valueOf(v.limits().currency());
		StringBuilder sb = new StringBuilder();
		sb.append("시드 ").append(cur.format(v.limits().budget())).append(" / 남은 예산 ").append(cur.format(v.available()))
			.append(" / 누적 실현손익 ").append(cur.format(v.realized())).append(" / 평가손익 ").append(cur.format(v.unrealized()));
		sb.append("\n종목당 한도 ").append(cur.format(v.limits().positionCap())).append(", 하루 매수 한도 ")
			.append(cur.format(v.limits().dailyBuyCap())).append(" (오늘 매수 ").append(cur.format(v.boughtToday()))
			.append("), 누적 손실 한도 ").append(cur.format(v.limits().lossLimitAmount()));
		if (v.open().isEmpty()) {
			sb.append("\n관리 중인 보유 종목: 없음");
		} else {
			sb.append("\n관리 중인 보유 종목:");
			for (AgentPosition p : v.open()) {
				sb.append("\n- ").append(p.getName()).append('(').append(p.getSymbol()).append(") ").append(p.getQuantity())
					.append("주, 평균 ").append(cur.format(p.getAvgPrice())).append(", 현재 ")
					.append(cur.format(p.getLastPrice() == null ? p.getAvgPrice() : p.getLastPrice()))
					.append(String.format(" (%+.2f%%)", p.pnlPct()));
			}
		}
		return sb.toString();
	}
}
