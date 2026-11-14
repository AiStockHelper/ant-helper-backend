package com.backend.domains.agent.skill;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.backend.common.exception.ApiException;
import com.backend.domains.agent.AgentPosition;
import com.backend.domains.agent.AgentTradingService;
import com.backend.domains.agent.AgentTradingService.LedgerView;
import com.backend.domains.trade.TradePlanner.Plan;
import com.backend.market.Currency;
import com.backend.market.Market;
import com.backend.market.dto.MarketDtos.OrderOutcome;

/**
 * 보유 종목을 정리하는 공통 규칙(익절 · 손절 · 보유일 초과 · 누적 손실 한도). 주문 스킬이 매수 판단과 별개로 같은 방식으로 쓴다.
 * 모든 매도는 {@link AgentTradingService} 를 거치므로 시드·한도·장부 기록이 그대로 적용된다.
 */
final class SkillExits {

	private SkillExits() {
	}

	/** 보유 종목 점검: 목표 수익 / 손절 / 최대 보유일 초과이면 매도한다. */
	static void manageOpenPositions(SkillContext ctx, Market group, ZonedDateTime now, double target, double stop, int maxHold) {
		var sv = ctx.services();
		AgentTradingService trading = sv.trading();
		long minutesToClose = Duration.between(now, now.with(group.closeTime())).toMinutes();
		LedgerView view = trading.view(ctx.task());
		for (AgentPosition p : view.open()) {
			if ((p.getMarket() == Market.KRX) != (group == Market.KRX)) {
				continue;
			}
			try {
				BigDecimal price = sv.marketData().quote(ctx.account(), p.getMarket(), p.getSymbol()).price();
				trading.mark(p.getId(), price);
				p.mark(price);
				double pct = p.pnlPct();
				String reason = null;
				if (pct >= target) {
					reason = String.format(Locale.ROOT, "목표 수익 도달 (%+.2f%%)", pct);
				} else if (pct <= -stop) {
					reason = String.format(Locale.ROOT, "손절선 도달 (%+.2f%%)", pct);
				} else if (maxHold > 0 && p.heldDays(group.zone()) >= maxHold && minutesToClose <= 40) {
					reason = String.format(Locale.ROOT, "최대 보유일(%d일) 초과 정리 (%+.2f%%)", maxHold, pct);
				}
				if (reason != null) {
					sell(ctx, p, reason);
				}
			} catch (ApiException e) {
				ctx.event(p.getName() + " 점검 중 문제: " + e.getMessage());
			}
		}
	}

	/** 누적 손실이 한도에 닿았으면 전량 정리하고 스킬을 멈추게 한다. 멈췄으면 true. */
	static boolean enforceLossLimit(SkillContext ctx) {
		AgentTradingService trading = ctx.services().trading();
		LedgerView view = trading.view(ctx.task());
		if (view.limits() == null || view.totalPnl().add(view.limits().lossLimitAmount()).signum() > 0) {
			return false;
		}
		ctx.event("누적 손실이 한도(" + Currency.valueOf(view.limits().currency()).format(view.limits().lossLimitAmount())
			+ ")에 도달했습니다. 보유 종목을 모두 정리하고 자동매매를 멈춥니다.");
		List<String> lines = new ArrayList<>();
		for (OrderOutcome o : trading.liquidate(ctx.task(), ctx.account(), lines)) {
			ctx.outcome(o);
		}
		lines.forEach(ctx::event);
		ctx.requestPause("누적 손실이 한도에 도달해 자동으로 멈췄습니다. 설정을 확인한 뒤 다시 시작해 주세요.");
		return true;
	}

	static void sell(SkillContext ctx, AgentPosition p, String reason) {
		Plan plan = ctx.services().planner().plan(ctx.account(), p.getSymbol(), p.getMarket().name(), "SELL", p.getQuantity(),
			null, null);
		OrderOutcome out = ctx.services().trading().sell(ctx.task(), ctx.account(), plan, reason);
		ctx.outcome(out);
		ctx.event(out.success()
			? String.format(Locale.ROOT, "%s %d주 매도 접수 · %s", p.getName(), p.getQuantity(), reason)
			: p.getName() + " 매도 실패: " + out.message() + " (" + reason + ")");
	}
}
