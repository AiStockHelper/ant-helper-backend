package com.backend.domains.chat.tool;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.backend.common.dto.PageResponse;
import com.backend.common.exception.ApiException;
import com.backend.common.exception.ErrorCode;
import com.backend.common.util.TimeUtil;
import com.backend.domains.chat.ActionService;
import com.backend.domains.chat.dto.ChatDtos.Action;
import com.backend.domains.chat.dto.ChatDtos.ScheduledTaskDto;
import com.backend.domains.member.domain.MemberAccount;
import com.backend.domains.schedule.ScheduleService;
import com.backend.domains.schedule.ScheduledTask;
import com.backend.domains.trade.TradePlanner;
import com.backend.domains.trade.TradePlanner.Plan;
import com.backend.market.Market;
import com.backend.market.MarketDataService;
import com.backend.market.MarketDataService.Period;
import com.backend.market.PredictionService;
import com.backend.market.TradingService;
import com.backend.market.dto.MarketDtos.AccountSummary;
import com.backend.market.dto.MarketDtos.Candle;
import com.backend.market.dto.MarketDtos.OrderHistoryItem;
import com.backend.market.dto.MarketDtos.OrderOutcome;
import com.backend.market.dto.MarketDtos.Prediction;
import com.backend.market.dto.MarketDtos.Quote;
import com.backend.market.dto.MarketDtos.Recommendation;
import com.backend.market.dto.MarketDtos.StockSummary;

import lombok.RequiredArgsConstructor;

/**
 * LLM 이 호출하는 도구 모음. 사용자/계좌는 인자로 받지 않고 {@link ChatSession} 에서 가져온다.
 * 결과는 (1) 화면용 카드로 내보내고 (2) LLM 에게는 요약된 JSON 만 돌려준다.
 */
@Component
@RequiredArgsConstructor
public class ChatTools {

	private static final DateTimeFormatter NOW_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd (E) HH:mm", java.util.Locale.KOREAN);

	private final MarketDataService marketData;
	private final PredictionService predictionService;
	private final TradingService tradingService;
	private final TradePlanner planner;
	private final ActionService actionService;
	private final ScheduleService scheduleService;

	// ─────────────────────────── 시세 ───────────────────────────

	@Tool(name = "search_stock", description = "종목명이나 티커/코드로 국내(KRX)와 미국(NASDAQ, NYSE, AMEX) 종목을 검색합니다. "
		+ "사용자가 종목을 이름으로 말하면 먼저 이 도구로 정확한 종목 코드와 시장을 확인하세요.")
	public Object searchStock(
		@ToolParam(description = "검색어. 예: 삼성전자, 애플, AAPL, 005930") String query,
		@ToolParam(description = "시장 필터: KRX, NASDAQ, NYSE, AMEX 중 하나. 생략하면 전체", required = false) String market,
		ToolContext ctx
	) {
		ChatSession s = session(ctx);
		List<StockSummary> found = marketData.search(query, parseMarket(market), 8);
		s.card("search", found);
		s.summary(found.size() + "개 종목");
		return found.stream().map(f -> map("symbol", f.productNumber(), "name", f.name(), "nameEn", f.nameEn(),
			"market", f.market(), "currency", f.currency())).toList();
	}

	@Tool(name = "get_quote", description = "종목의 현재가와 등락, 시가/고가/저가, 거래량을 조회합니다. 국내와 미국 모두 지원합니다.")
	public Object getQuote(
		@ToolParam(description = "종목 코드/티커 또는 이름. 예: 005930, AAPL, 삼성전자") String symbol,
		@ToolParam(description = "시장(KRX, NASDAQ, NYSE, AMEX). 모르면 생략", required = false) String market,
		ToolContext ctx
	) {
		ChatSession s = session(ctx);
		MemberAccount account = s.account();
		StockSummary stock = marketData.resolve(symbol, parseMarket(market));
		Quote q = marketData.quote(account, stock.market(), stock.productNumber());
		s.card("quote", q);
		s.summary(q.name() + " " + q.currency().format(q.price()));
		return map("symbol", q.productNumber(), "name", q.name(), "market", q.market(), "currency", q.currency(),
			"price", q.price(), "priceText", q.currency().format(q.price()), "change", q.change(),
			"changeRatePercent", q.changeRate(), "open", q.open(), "high", q.high(), "low", q.low(), "volume", q.volume());
	}

	@Tool(name = "get_candles", description = "종목의 일/주/월 봉 차트 데이터를 조회해 차트 카드로 보여줍니다.")
	public Object getCandles(
		@ToolParam(description = "종목 코드/티커 또는 이름") String symbol,
		@ToolParam(description = "시장(KRX, NASDAQ, NYSE, AMEX). 모르면 생략", required = false) String market,
		@ToolParam(description = "기간 단위: D(일), W(주), M(월). 기본 D", required = false) String period,
		@ToolParam(description = "가져올 봉 개수(최대 200). 기본 60", required = false) Integer count,
		ToolContext ctx
	) {
		ChatSession s = session(ctx);
		StockSummary stock = marketData.resolve(symbol, parseMarket(market));
		Period p = Period.parse(period);
		List<Candle> candles = marketData.candles(s.account(), stock.market(), stock.productNumber(), p,
			count == null ? 60 : Math.max(5, Math.min(count, 200)));
		s.card("candles", map("productNumber", stock.productNumber(), "name", stock.name(), "market", stock.market(),
			"currency", stock.currency(), "period", p.name(), "candles", candles));
		if (candles.isEmpty()) {
			return map("error", "차트 데이터가 없습니다.");
		}
		BigDecimal high = candles.stream().map(Candle::high).max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
		BigDecimal low = candles.stream().map(Candle::low).min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
		List<Candle> tail = candles.subList(Math.max(0, candles.size() - 5), candles.size());
		s.summary(stock.name() + " " + candles.size() + "개 봉");
		return map("symbol", stock.productNumber(), "name", stock.name(), "currency", stock.currency(), "period", p.name(),
			"count", candles.size(), "firstDate", candles.get(0).date(), "lastDate", candles.get(candles.size() - 1).date(),
			"periodHigh", high, "periodLow", low, "recentCandles", tail);
	}

	// ─────────────────────────── 예측 / 추천 ───────────────────────────

	@Tool(name = "predict_stock", description = "AI 모델로 종목의 다음 거래일 방향과 예상 가격을 예측합니다. 결과는 통계적 참고 지표이며 투자 권유가 아닙니다.")
	public Object predictStock(
		@ToolParam(description = "종목 코드/티커 또는 이름") String symbol,
		@ToolParam(description = "시장(KRX, NASDAQ, NYSE, AMEX). 모르면 생략", required = false) String market,
		ToolContext ctx
	) {
		ChatSession s = session(ctx);
		StockSummary stock = marketData.resolve(symbol, parseMarket(market));
		Prediction p = predictionService.predict(s.account(), stock.market(), stock.productNumber());
		s.card("prediction", p);
		s.summary(p.name() + " " + PredictionService.directionLabel(p.direction()) + " 예상");
		return map("symbol", p.productNumber(), "name", p.name(), "market", p.market(), "currency", p.currency(),
			"currentPrice", p.currentPrice(), "predictedPrice", p.predictedPrice(),
			"predictedChangeRatePercent", p.predictedChangeRate(), "direction", p.direction(),
			"confidence", p.confidence(), "summary", p.summary(),
			"signals", p.signals().stream().map(x -> x.name() + ": " + x.value() + " (" + x.tone() + ")").toList(),
			"horizon", "다음 거래일", "disclaimer", "통계 모델의 참고 지표이며 수익을 보장하지 않습니다.");
	}

	@Tool(name = "recommend_stocks", description = "대표 종목 후보군을 AI 모델로 분석해 내일 상승 가능성이 높은 추천 종목을 순위로 보여줍니다.")
	public Object recommendStocks(
		@ToolParam(description = "KRX(한국, 기본) 또는 US(미국)", required = false) String market,
		@ToolParam(description = "추천 개수(1~10). 기본 5", required = false) Integer count,
		ToolContext ctx
	) {
		ChatSession s = session(ctx);
		String group = market != null && (market.equalsIgnoreCase("US") || market.equalsIgnoreCase("NASDAQ")
			|| market.equalsIgnoreCase("NYSE") || market.equalsIgnoreCase("AMEX")) ? "US" : "KRX";
		int n = count == null ? 5 : Math.max(1, Math.min(count, 10));
		List<Recommendation> recs = predictionService.recommend(s.account(), group, n);
		s.card("recommendations", recs);
		s.summary(recs.size() + "개 종목");
		return recs.stream().map(r -> map("symbol", r.productNumber(), "name", r.name(), "market", r.market(),
			"currency", r.currency(), "currentPrice", r.currentPrice(), "score", r.score(),
			"predictedChangeRatePercent", r.predictedChangeRate(), "direction", r.direction(),
			"confidence", r.confidence(), "reasons", r.reasons())).toList();
	}

	// ─────────────────────────── 계좌 ───────────────────────────

	@Tool(name = "get_account_summary", description = "현재 선택된 계좌의 예수금(현금), 총 평가금액, 평가손익, 보유 종목(국내+미국)을 조회합니다.")
	public Object getAccountSummary(ToolContext ctx) {
		ChatSession s = session(ctx);
		MemberAccount account = s.account();
		AccountSummary sum = tradingService.summary(account);
		s.card("balance", sum);
		s.summary(account.displayName() + " 잔고");
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("account", account.displayName() + (account.isPaper() ? " (모의)" : " (실전)"));
		out.put("domestic", map("currency", "KRW", "cash", sum.cash(), "totalAssets", sum.totalAssets(),
			"purchaseAmount", sum.totalPurchaseAmount(), "evaluationAmount", sum.totalEvaluationAmount(),
			"profitLoss", sum.profitLoss(), "profitLossRatePercent", sum.profitLossRate(),
			"holdings", sum.holdings().stream().map(h -> map("symbol", h.productNumber(), "name", h.name(), "quantity", h.quantity(),
				"averagePrice", h.averagePrice(), "currentPrice", h.currentPrice(), "profitLoss", h.profitLoss(),
				"profitLossRatePercent", h.profitLossRate())).toList()));
		if (sum.overseas() != null) {
			out.put("overseas", map("currency", "USD", "cash", sum.overseas().cash(), "totalAssets", sum.overseas().totalAssets(),
				"profitLoss", sum.overseas().profitLoss(), "profitLossRatePercent", sum.overseas().profitLossRate(),
				"holdings", sum.overseas().holdings().stream().map(h -> map("symbol", h.productNumber(), "name", h.name(),
					"market", h.market(), "quantity", h.quantity(), "averagePrice", h.averagePrice(), "currentPrice", h.currentPrice(),
					"profitLoss", h.profitLoss(), "profitLossRatePercent", h.profitLossRate())).toList()));
			out.put("exchangeRateUsdKrw", sum.exchangeRate());
		}
		return out;
	}

	@Tool(name = "get_order_history", description = "최근 주문/체결 내역을 조회합니다.")
	public Object getOrderHistory(
		@ToolParam(description = "DOMESTIC(국내, 기본) 또는 OVERSEAS(해외)", required = false) String scope,
		@ToolParam(description = "최근 며칠(1~90). 기본 14", required = false) Integer days,
		ToolContext ctx
	) {
		ChatSession s = session(ctx);
		boolean overseas = scope != null && scope.equalsIgnoreCase("OVERSEAS");
		LocalDate to = LocalDate.now(TimeUtil.SEOUL);
		LocalDate from = to.minusDays(days == null ? 14 : Math.max(1, Math.min(days, 90)));
		PageResponse<OrderHistoryItem> page = tradingService.orderHistory(s.account(), overseas, from, to, 0, 30);
		s.card("orders", page.getContent());
		s.summary(page.getContent().size() + "건");
		return page.getContent().stream().map(o -> map("orderNo", o.orderNo(), "orderedAt", o.orderedAt(), "side", o.side(),
			"symbol", o.productNumber(), "name", o.name(), "market", o.market(), "orderQuantity", o.orderQuantity(),
			"orderPrice", o.orderPrice(), "executedQuantity", o.executedQuantity(), "averageExecutedPrice",
			o.averageExecutedPrice(), "status", o.status())).toList();
	}

	// ─────────────────────────── 주문 ───────────────────────────

	@Tool(name = "place_order", description = "주식을 매수/매도 주문합니다. 사용자가 명확하게 주문을 요청했을 때만 호출하세요. "
		+ "수량(quantity) 또는 금액(amount) 중 하나는 반드시 있어야 하며, 없으면 사용자에게 먼저 물어보세요. "
		+ "모의 계좌는 즉시 주문되고, 실전 계좌는 사용자가 확인 카드에서 승인해야 실행됩니다. "
		+ "미국 주식은 지정가만 가능하며 가격을 생략하면 현재가 기준 지정가로 변환됩니다.")
	public Object placeOrder(
		@ToolParam(description = "종목 코드/티커 또는 이름") String symbol,
		@ToolParam(description = "BUY(매수) 또는 SELL(매도)") String side,
		@ToolParam(description = "수량(주). 금액(amount)을 쓰면 생략", required = false) Long quantity,
		@ToolParam(description = "주문 금액(해당 종목 통화 기준: 국내 원, 미국 달러). 수량을 모를 때 사용", required = false) Double amount,
		@ToolParam(description = "지정가. 생략하면 국내는 시장가, 미국은 현재가 기준 지정가", required = false) Double price,
		@ToolParam(description = "시장(KRX, NASDAQ, NYSE, AMEX). 모르면 생략", required = false) String market,
		ToolContext ctx
	) {
		ChatSession s = session(ctx);
		if (!s.allowOrderCall()) {
			throw ApiException.of(ErrorCode.ORDER_INVALID, "한 번의 답변에서 만들 수 있는 주문 수를 초과했습니다.");
		}
		MemberAccount account = s.account();
		Plan plan = planner.plan(account, symbol, market, side, quantity, bd(amount), bd(price));
		planner.enforceLimits(account, plan);
		planner.requireMarketOpen(plan.market());

		if (account.isPaper()) {
			OrderOutcome out = planner.execute(account, plan);
			s.card("order_result", ScheduleService.orderResultCard(out));
			s.summary(out.success() ? plan.name() + " " + plan.quantity() + "주 " + plan.sideLabel() + " 접수" : "주문 실패");
			return map("status", out.success() ? "SUBMITTED" : "REJECTED", "message", out.message(), "orderNo", out.orderNo(),
				"name", plan.name(), "side", plan.side(), "quantity", plan.quantity(), "currency", plan.currency(),
				"orderType", plan.orderTypeLabel(), "price", plan.limitPrice() != null ? plan.limitPrice() : plan.refPrice(),
				"account", "모의 계좌");
		}
		Action action = actionService.createOrderAction(account, s.conversationId(), s.assistantMessageId(), plan);
		s.action(action);
		s.summary("확인 카드 생성: " + action.title());
		return map("status", "PENDING_USER_APPROVAL",
			"message", "주문은 아직 실행되지 않았습니다. 사용자에게 확인 카드가 표시되었으며 승인해야 주문이 전송됩니다. "
				+ "승인 전에는 접수/체결되었다고 말하지 마세요.",
			"name", plan.name(), "side", plan.side(), "quantity", plan.quantity(), "currency", plan.currency(),
			"orderType", plan.orderTypeLabel(), "estimatedAmount", plan.estimatedAmount(), "account", "실전 계좌");
	}

	@Tool(name = "cancel_order", description = "접수된 미체결 주문을 취소합니다. 주문번호는 get_order_history 로 확인할 수 있습니다.")
	public Object cancelOrder(
		@ToolParam(description = "취소할 주문번호") String orderNo,
		@ToolParam(description = "주문한 종목 코드/티커") String symbol,
		@ToolParam(description = "시장(KRX, NASDAQ, NYSE, AMEX). 모르면 생략", required = false) String market,
		ToolContext ctx
	) {
		ChatSession s = session(ctx);
		if (!s.allowOrderCall()) {
			throw ApiException.of(ErrorCode.ORDER_INVALID, "한 번의 답변에서 처리할 수 있는 주문 수를 초과했습니다.");
		}
		StockSummary stock = marketData.resolve(symbol, parseMarket(market));
		OrderOutcome out = tradingService.cancelOrder(s.account(), stock.market(), stock.productNumber(), orderNo);
		s.card("order_result", ScheduleService.orderResultCard(out));
		s.summary(out.success() ? "취소 접수" : "취소 실패");
		return map("status", out.success() ? "CANCEL_SUBMITTED" : "FAILED", "message", out.message());
	}

	// ─────────────────────────── 예약 ───────────────────────────

	@Tool(name = "schedule_order", description = "지정한 시각에 특정 종목을 자동으로 매수/매도하도록 예약합니다. "
		+ "시각은 반드시 현재 시각을 기준으로 계산한 절대 시각(ISO 8601, 예 2026-10-25T09:00)으로 전달하세요. "
		+ "오프셋이 없으면 해당 시장의 현지 시간(한국=KST, 미국=뉴욕)으로 해석됩니다. 실전 계좌는 확인 카드 승인 후 등록됩니다.")
	public Object scheduleOrder(
		@ToolParam(description = "종목 코드/티커 또는 이름") String symbol,
		@ToolParam(description = "BUY(매수) 또는 SELL(매도)") String side,
		@ToolParam(description = "실행 시각. 예: 2026-10-25T09:00") String runAt,
		@ToolParam(description = "수량(주). 금액(amount)을 쓰면 생략", required = false) Long quantity,
		@ToolParam(description = "주문 금액(해당 종목 통화 기준). 실행 시점의 시세로 수량이 계산됨", required = false) Double amount,
		@ToolParam(description = "지정가. 생략하면 국내 시장가/미국 현재가 기준 지정가", required = false) Double price,
		@ToolParam(description = "시장(KRX, NASDAQ, NYSE, AMEX). 모르면 생략", required = false) String market,
		ToolContext ctx
	) {
		ChatSession s = session(ctx);
		if (!s.allowOrderCall()) {
			throw ApiException.of(ErrorCode.ORDER_INVALID, "한 번의 답변에서 만들 수 있는 예약 수를 초과했습니다.");
		}
		MemberAccount account = s.account();
		Plan plan = planner.plan(account, symbol, market, side, quantity, bd(amount), bd(price));
		planner.enforceLimits(account, plan);
		java.time.Instant at = scheduleService.parseRunAt(runAt, plan.market());
		String when = scheduleService.humanTime(at, plan.market());

		Map<String, Object> params = new LinkedHashMap<>();
		params.put("market", plan.market().name());
		params.put("productNumber", plan.symbol());
		params.put("name", plan.name());
		params.put("side", plan.side());
		if (quantity != null && quantity > 0) {
			params.put("quantity", quantity);
		}
		if (amount != null && amount > 0) {
			params.put("amount", amount);
		}
		if (price != null && price > 0) {
			params.put("price", price);
		}
		String size = quantity != null && quantity > 0 ? quantity + "주" : plan.currency().format(bd(amount));
		String title = when + " " + plan.name() + " " + size + " " + plan.sideLabel();
		return finishSchedule(s, account, ScheduledTask.Kind.ORDER, title, at, when, params, plan, null, null, 0);
	}

	@Tool(name = "schedule_recommend_buy", description = "지정한 시각에 AI 추천 종목을 골라 정해진 예산 한도 안에서 자동 매수하도록 예약합니다. "
		+ "예: '내일 오전 9시에 추천 종목 200만원어치 사줘'. 실행 시점에 상승이 예측되는 상위 종목만 매수하고 예산을 균등 배분합니다. "
		+ "시각은 현재 시각 기준으로 계산한 절대 시각(ISO 8601)으로 전달하세요.")
	public Object scheduleRecommendBuy(
		@ToolParam(description = "총 예산(국내는 원, 미국은 달러)") Double budget,
		@ToolParam(description = "실행 시각. 예: 2026-10-25T09:00") String runAt,
		@ToolParam(description = "KRX(한국, 기본) 또는 US(미국)", required = false) String market,
		@ToolParam(description = "매수할 종목 수(1~10). 기본 3", required = false) Integer count,
		ToolContext ctx
	) {
		ChatSession s = session(ctx);
		if (!s.allowOrderCall()) {
			throw ApiException.of(ErrorCode.ORDER_INVALID, "한 번의 답변에서 만들 수 있는 예약 수를 초과했습니다.");
		}
		if (budget == null || budget <= 0) {
			throw ApiException.of(ErrorCode.ORDER_INVALID, "예산 금액이 필요합니다.");
		}
		MemberAccount account = s.account();
		boolean us = market != null && (market.equalsIgnoreCase("US") || market.equalsIgnoreCase("NASDAQ")
			|| market.equalsIgnoreCase("NYSE") || market.equalsIgnoreCase("AMEX"));
		Market zoneMarket = us ? Market.NASDAQ : Market.KRX;
		int n = count == null ? 3 : Math.max(1, Math.min(count, 10));
		BigDecimal budgetValue = zoneMarket.currency().round(BigDecimal.valueOf(budget));

		// 실전 계좌는 총 예산도 1회 주문 한도 이내로 제한한다.
		if (!account.isPaper()) {
			planner.enforceLimits(account, new Plan(zoneMarket, "-", "-", "BUY", 1, budgetValue, budgetValue, ""));
		}
		java.time.Instant at = scheduleService.parseRunAt(runAt, zoneMarket);
		String when = scheduleService.humanTime(at, zoneMarket);

		Map<String, Object> params = new LinkedHashMap<>();
		params.put("market", us ? "US" : "KRX");
		params.put("budget", budgetValue);
		params.put("count", n);
		String title = when + " 추천 종목 " + zoneMarket.currency().format(budgetValue) + " 매수";
		return finishSchedule(s, account, ScheduledTask.Kind.RECOMMEND_BUY, title, at, when, params, null, zoneMarket,
			budgetValue, n);
	}

	private Object finishSchedule(ChatSession s, MemberAccount account, ScheduledTask.Kind kind, String title,
		java.time.Instant at, String when, Map<String, Object> params, Plan plan, Market zoneMarket, BigDecimal budget,
		int count) {
		if (account.isPaper()) {
			ScheduledTask t = scheduleService.create(account, s.conversationId(), kind, title, at, params);
			ScheduledTaskDto dto = scheduleService.list(s.memberId(), account.getId(), null).stream()
				.filter(x -> x.id().equals(t.getId())).findFirst().orElse(null);
			s.card("scheduled_tasks", dto == null ? List.of() : List.of(dto));
			s.summary("예약 등록: " + when);
			return map("status", "SCHEDULED", "taskId", t.getId(), "runAt", when, "title", title, "account", "모의 계좌",
				"note", "해당 시각에 서버가 자동으로 실행하고, 결과를 이 대화에 남깁니다.");
		}
		Action action = kind == ScheduledTask.Kind.ORDER
			? actionService.createScheduledOrderAction(account, s.conversationId(), s.assistantMessageId(), plan, params, at, when, title)
			: actionService.createScheduledRecommendBuyAction(account, s.conversationId(), s.assistantMessageId(), zoneMarket,
				budget, count, at, when, title, params);
		s.action(action);
		s.summary("확인 카드 생성: " + title);
		return map("status", "PENDING_USER_APPROVAL", "runAt", when, "title", title, "account", "실전 계좌",
			"message", "예약은 아직 등록되지 않았습니다. 사용자가 확인 카드에서 승인해야 등록됩니다. 승인 전에는 예약되었다고 말하지 마세요.");
	}

	@Tool(name = "list_scheduled_tasks", description = "현재 계좌에 등록된 예약 주문 목록(대기 중인 것 위주)을 조회합니다.")
	public Object listScheduledTasks(ToolContext ctx) {
		ChatSession s = session(ctx);
		List<ScheduledTaskDto> list = scheduleService.list(s.memberId(), s.account().getId(), null);
		s.card("scheduled_tasks", list);
		s.summary(list.size() + "건");
		return list.stream().limit(20).map(t -> map("taskId", t.id(), "title", t.title(), "status", t.status(),
			"runAt", t.runAt(), "result", t.resultMessage())).toList();
	}

	@Tool(name = "cancel_scheduled_task", description = "대기 중인 예약 작업을 취소합니다.")
	public Object cancelScheduledTask(@ToolParam(description = "취소할 예약 번호(taskId)") Long taskId, ToolContext ctx) {
		ChatSession s = session(ctx);
		ScheduledTaskDto dto = scheduleService.cancel(s.memberId(), taskId);
		s.card("scheduled_tasks", List.of(dto));
		s.summary("예약 " + taskId + " 취소");
		return map("status", dto.status(), "title", dto.title());
	}

	// ─────────────────────────── 시장 상태 ───────────────────────────

	@Tool(name = "get_market_status", description = "현재 시각과 한국/미국 정규장 개장 여부를 확인합니다. 예약 시각을 정하기 전이나 장 운영 시간을 물을 때 사용하세요.")
	public Object getMarketStatus(ToolContext ctx) {
		ChatSession s = session(ctx);
		ZonedDateTime now = ZonedDateTime.now();
		List<Map<String, Object>> markets = new ArrayList<>();
		for (Market m : new Market[] {Market.KRX, Market.NASDAQ}) {
			ZonedDateTime local = now.withZoneSameInstant(m.zone());
			markets.add(map("market", m == Market.KRX ? "한국(KRX)" : "미국(NYSE/NASDAQ/AMEX)", "open", m.isOpenAt(now),
				"localTime", local.format(NOW_FMT), "regularHours", m.openTime() + "~" + m.closeTime() + " 현지 시간"));
		}
		s.summary("장 운영 상태");
		return map("nowKst", now.withZoneSameInstant(TimeUtil.SEOUL).format(NOW_FMT), "markets", markets,
			"note", "공휴일은 반영하지 않습니다. 주말은 휴장입니다.");
	}

	// ─────────────────────────── 유틸 ───────────────────────────

	private ChatSession session(ToolContext ctx) {
		Object s = ctx == null ? null : ctx.getContext().get("session");
		if (!(s instanceof ChatSession cs)) {
			throw ApiException.from(ErrorCode.INTERNAL_SERVER_ERROR);
		}
		return cs;
	}

	private Market parseMarket(String market) {
		return market == null || market.isBlank() ? null : Market.parse(market);
	}

	private static BigDecimal bd(Double v) {
		return v == null ? null : BigDecimal.valueOf(v);
	}

	private static Map<String, Object> map(Object... kv) {
		Map<String, Object> m = new LinkedHashMap<>();
		for (int i = 0; i + 1 < kv.length; i += 2) {
			m.put(String.valueOf(kv[i]), kv[i + 1]);
		}
		return m;
	}
}
