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
import com.backend.domains.agent.AgentDtos.AgentTaskDto;
import com.backend.domains.agent.AgentTask;
import com.backend.domains.agent.AgentTaskService;
import com.backend.domains.agent.AgentTradingService;
import com.backend.domains.agent.Limits;
import com.backend.domains.agent.OrderCards;
import com.backend.domains.agent.Schedule;
import com.backend.domains.agent.skill.SkillRegistry;
import com.backend.domains.chat.ActionService;
import com.backend.domains.chat.dto.ChatDtos.Action;
import com.backend.domains.member.domain.MemberAccount;
import com.backend.domains.trade.TradePlanner;
import com.backend.domains.trade.TradePlanner.Plan;
import com.backend.domains.screening.ScreeningService;
import com.backend.market.Currency;
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
import com.backend.order.kis.kis_client.exception.KisClientException;

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
	private final AgentTaskService agentTasks;
	private final AgentTradingService agentTrading;
	private final SkillRegistry skills;
	private final ScreeningService screening;
	private final com.backend.domains.chat.memory.ChatHistorySearchService historySearch;
	private final com.backend.domains.account.AccountService accounts;

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

	@Tool(name = "recommend_stocks", description = "'모델 추천' 카드를 화면에 띄웁니다. 야간 배치가 한국·미국 유동성 상위 종목의 변동성·RSI·단기 등락 등을 비교해 점수를 매긴 결과이며, "
		+ "과거 검증에서 방향 예측보다 '변동성이 낮고 최근 덜 오른(눌린) 종목'이 일관되게 유리해 그 기준으로 순위를 매깁니다. "
		+ "종목 추천 요청에서는 이 도구를 가장 먼저 부르되, 기본적으로 모델의 종목·점수는 당신에게 공개되지 않습니다(카드로만 사용자에게 보여 줍니다). "
		+ "당신의 추천은 모델과 무관하게 market_scan 으로 직접 조사해서 정해야 합니다. "
		+ "사용자가 명시적으로 '모델 추천만' 알려 달라고 할 때만 modelOnly=true 로 불러 내용을 받아 설명하세요.")
	public Object recommendStocks(
		@ToolParam(description = "KRX(한국, 기본) 또는 US(미국)", required = false) String market,
		@ToolParam(description = "추천 개수(1~10). 기본 5", required = false) Integer count,
		@ToolParam(description = "true 면 모델 추천 내용을 당신에게도 돌려줍니다. 사용자가 모델 추천만 원할 때만 사용", required = false) Boolean modelOnly,
		ToolContext ctx
	) {
		ChatSession s = session(ctx);
		String group = ScreeningService.normalizeGroup(market);
		int n = count == null ? 5 : Math.max(1, Math.min(count, 10));
		MemberAccount account = s.account();
		List<Recommendation> picks = predictionService.withLivePrices(account, predictionService.modelPicks(account, group, n));
		s.rememberCandidates(picks, picks);
		String basis = !picks.isEmpty() && picks.get(0).asOf() != null ? picks.get(0).asOf() + " 장마감 기준 야간 분석" : "실시간 분석(대표 종목 후보군)";
		if (!picks.isEmpty()) {
			s.card("recommendations", picks);
		}
		s.summary(picks.size() + "개 종목");
		if (!Boolean.TRUE.equals(modelOnly)) {
			return map("status", picks.isEmpty() ? "NO_MODEL_PICKS" : "MODEL_CARD_SHOWN", "modelPickCount", picks.size(), "basis", basis,
				"note", "모델 추천 카드가 사용자 화면에 표시되었습니다. 모델의 종목과 점수는 당신에게 공개되지 않으며, 당신의 추천은 모델을 참고하지 않고 "
					+ "market_scan·get_quote·get_candles 로 직접 조사해 정해야 합니다. 이제 market_scan 으로 시장을 훑어보세요.");
		}
		List<Object> pickMaps = new ArrayList<>();
		for (int i = 0; i < picks.size(); i++) {
			pickMaps.add(recMap(picks.get(i), i + 1));
		}
		return map("basis", basis, "modelPicks", pickMaps,
			"note", picks.isEmpty() ? "점수 기준을 만족하는 종목이 없습니다. 억지로 추천하지 말고 그대로 알리세요." : null);
	}

	@Tool(name = "market_scan", description = "모델과 무관한 시장 원자료를 봅니다. 야간에 수집한 거래대금 상위 종목(추천 대상과 같은 범위)을 기준별로 정렬해 "
		+ "각 종목의 20일 변동성(vol20,%), RSI, 최근 1·5·20일 수익률(ret1/ret5/ret20,%), 60일 수익률(mom60), 20·60일선 대비 위치(pxMa20/pxMa60,%), "
		+ "정배열 여부(align), 거래량 배수(volRatio), 60일 고점 대비(distHigh60,%), 거래대금(avgTradeValue)을 보여 줍니다. 모델 점수나 방향 예측은 포함되지 않습니다. "
		+ "view: LIQUID(거래대금 큰 순), STABLE(변동성 낮은 순), VOLATILE, OVERSOLD(RSI 낮은 순), OVERBOUGHT, DIP(5일 수익 낮은 순), RALLY(5일 수익 높은 순), "
		+ "MOMENTUM(60일 수익 높은 순), VOLUME_SURGE(거래량 급증), NEAR_HIGH(60일 고점 근접). 종목 추천을 할 때는 서로 다른 view 를 두세 개 이상 보고 종합해 판단하세요.")
	public Object marketScan(
		@ToolParam(description = "KRX(한국, 기본) 또는 US(미국)", required = false) String market,
		@ToolParam(description = "정렬 기준(위 목록). 기본 LIQUID", required = false) String view,
		@ToolParam(description = "가져올 종목 수(1~30). 기본 20", required = false) Integer count,
		ToolContext ctx
	) {
		ChatSession s = session(ctx);
		String group = ScreeningService.normalizeGroup(market);
		ScreeningService.ScanView v = ScreeningService.ScanView.parse(view);
		int n = count == null ? 20 : Math.max(1, Math.min(count, 30));
		List<ScreeningService.ScanRow> rows = screening.scan(group, v, n);
		if (rows.isEmpty()) {
			return map("error", "야간 분석 데이터가 아직 없습니다. get_quote·get_candles 로 종목을 직접 확인하세요.");
		}
		s.summary(v.label() + " " + rows.size() + "종목");
		return map("market", group, "view", v.name(), "viewLabel", v.label(),
			"currencyNote", "KRX 는 원, US 는 달러 단위. avgTradeValue 는 최근 20일 평균 거래대금(해당 통화).",
			"rows", rows.stream().map(r -> map("symbol", r.productNumber(), "name", r.name(), "market", r.market(),
				"currency", r.currency(), "price", r.price(), "avgTradeValue", Math.round(r.avgTradeValue()), "facts", r.facts())).toList());
	}
	private static Map<String, Object> recMap(Recommendation r, int rank) {
		return map("modelRank", rank == 0 ? null : rank, "symbol", r.productNumber(), "name", r.name(), "market", r.market(),
			"currency", r.currency(), "currentPrice", r.currentPrice(), "score", r.score(),
			"predictedChangeRatePercent", r.predictedChangeRate(), "direction", r.direction(),
			"confidence", r.confidence(), "reasons", r.reasons().stream().limit(5).toList());
	}

	public record LlmPick(String symbol, String market, String reason, String outlook) {
	}

	@Tool(name = "show_llm_picks", description = "당신(AI)이 직접 조사해 고른 종목과 이유를 'AI 의견' 카드로 보여줍니다. 모델 추천과 무관하게, market_scan 의 여러 기준·get_quote·get_candles 로 확인한 사실을 종합해 고르세요"
		+ "(변동성·수익률 흐름·과열 여부·거래량·분산 등). 후보는 market_scan 에 나온 종목이 기본이지만 search_stock 으로 확인한 다른 종목도 괜찮습니다. "
		+ "이유(reason)는 조회한 숫자에 근거해 한두 문장으로 쓰고, 실시간 뉴스·공시·재무는 확인하지 못했으므로 지어내지 마세요. "
		+ "서버가 실제 시세를 조회해 카드에 표시하며 존재하지 않는 종목은 거절됩니다.")	public Object showLlmPicks(
		@ToolParam(description = "당신이 고른 종목 목록(최대 10개). symbol=종목 코드/티커, market=KRX/NASDAQ/NYSE/AMEX(모르면 생략), reason=근거, outlook='단기 상승 기대', '관망', '변동성 주의' 같은 한 줄 전망") List<LlmPick> picks,
		ToolContext ctx
	) {
		ChatSession s = session(ctx);
		MemberAccount account = s.account();
		if (picks == null || picks.isEmpty()) {
			throw ApiException.of(ErrorCode.INVALID_PARAMETER, "고른 종목이 없습니다.");
		}
		List<Map<String, Object>> shown = new ArrayList<>();
		List<String> rejected = new ArrayList<>();
		for (LlmPick p : picks.stream().limit(10).toList()) {
			String sym = p.symbol() == null ? "" : p.symbol().trim();
			try {
				shown.add(describePick(s, account, sym, p));
			} catch (RuntimeException e) {
				rejected.add(sym + " (" + (e instanceof ApiException || e instanceof KisClientException ? e.getMessage() : "조회 실패") + ")");
			}
		}
		if (shown.isEmpty()) {
			throw ApiException.of(ErrorCode.INVALID_PARAMETER, "고른 종목을 확인하지 못했습니다: " + String.join(", ", rejected));
		}
		s.card("llm_recommendations", map("picks", shown));
		s.summary("AI 의견 " + shown.size() + "종목");
		List<Object> overlap = shown.stream().filter(m -> Boolean.TRUE.equals(m.get("inModelTop"))).map(m -> m.get("productNumber")).toList();
		List<Object> noModel = shown.stream().filter(m -> !Boolean.TRUE.equals(m.get("modelAnalyzed"))).map(m -> m.get("productNumber")).toList();
		return map("status", "SHOWN", "shown", shown.size(), "overlapWithModelTop", overlap, "withoutModelAnalysis", noModel,
			"rejected", rejected,
			"note", "카드가 표시되었습니다. 답변은 '모델 추천'(카드 참고)과 'AI 의견'을 구분해 짧게 정리하되, 당신의 의견은 모델과 무관하게 조사한 결과임을 전제로 설명하세요. 겹치는 종목이 있으면 우연히 같은 결론이 나왔다고만 알리세요.");
	}

	/** AI 가 고른 종목 하나를 실제 데이터로 검증하고 카드 항목으로 만든다. 존재하지 않으면 예외. */
	private Map<String, Object> describePick(ChatSession s, MemberAccount account, String sym, LlmPick p) {
		Recommendation pooled = s.candidates().stream()
			.filter(r -> r.productNumber().equalsIgnoreCase(sym)).findFirst().orElse(null);
		StockSummary stock = pooled != null
			? new StockSummary(pooled.productNumber(), pooled.name(), null, pooled.market(), pooled.currency())
			: marketData.resolve(sym, parseMarket(p.market()));
		String group = ScreeningService.normalizeGroup(stock.market().name());
		BigDecimal price;
		try {
			price = marketData.quote(account, stock.market(), stock.productNumber()).price();
		} catch (RuntimeException e) {
			if (pooled == null) {
				throw e;
			}
			price = pooled.currentPrice();
		}
		Double score = null;
		String direction = null;
		Double change = null;
		Long rank = null;
		Long total = null;
		var ranked = screening.lookup(group, stock.productNumber());
		if (ranked.isPresent()) {
			var row = ranked.get().row();
			score = row.getScore();
			direction = row.getDirection();
			change = row.getPredictedChangeRate();
			rank = ranked.get().rank();
			total = ranked.get().total();
		} else if (pooled != null) {
			score = pooled.score();
			direction = pooled.direction();
			change = pooled.predictedChangeRate();
		} else {
			try {
				Prediction pr = predictionService.predict(account, stock.market(), stock.productNumber());
				direction = pr.direction();
				change = pr.predictedChangeRate();
			} catch (RuntimeException ignored) {
				// 모델 분석을 못 하면 '모델 분석 없음'으로 표시한다.
			}
		}
		return map("productNumber", stock.productNumber(), "name", stock.name(), "market", stock.market(),
			"currency", stock.currency(), "currentPrice", price, "modelRank", rank, "modelTotal", total,
			"inModelTop", s.isModelPick(stock.productNumber()), "modelAnalyzed", direction != null,
			"modelScore", score, "modelDirection", direction, "modelPredictedChangeRate", change,
			"reason", p.reason() == null ? "" : p.reason().trim(), "outlook", p.outlook() == null ? "" : p.outlook().trim());
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

		if (s.agentTaskId() != null) {
			return placeAgentOrder(s, account, plan);
		}
		if (account.isPaper()) {
			OrderOutcome out = planner.execute(account, plan, com.backend.market.queue.OrderSource.chat(null, s.conversationId()));
			s.card("order_result", OrderCards.orderResult(out));
			s.summary(out.success() ? plan.name() + " " + plan.quantity() + "주 " + plan.sideLabel() + " 접수" : "주문 실패");
			return map("status", out.success() ? "SUBMITTED" : out.pending() ? "QUEUED_FOR_RETRY" : "REJECTED", "message", out.message(), "orderNo", out.orderNo(),
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

	/** 사람이 없는 자동 실행의 주문: 한도를 코드로 검사하고 장부에 기록하는 AgentTradingService 를 거친다. */
	private Object placeAgentOrder(ChatSession s, MemberAccount account, Plan plan) {
		if (!s.autonomous()) {
			throw ApiException.of(ErrorCode.ORDER_INVALID,
				"이 자동 실행은 실전 계좌에서 사용자 확인 없이 주문할 수 없습니다. 주문 대신 결과 요약에 제안으로 적어 주세요.");
		}
		AgentTask task = agentTasks.requireById(s.agentTaskId());
		OrderOutcome out = "BUY".equals(plan.side())
			? agentTrading.buy(task, account, plan, "AI 판단")
			: agentTrading.sell(task, account, plan, "AI 판단");
		if (out.success()) {
			s.orderAccepted();
		}
		s.card("order_result", OrderCards.orderResult(out));
		s.summary(out.success() ? plan.name() + " " + plan.quantity() + "주 " + plan.sideLabel() + " 접수" : "주문 실패");
		return map("status", out.success() ? "SUBMITTED" : out.pending() ? "QUEUED_FOR_RETRY" : "REJECTED", "message", out.message(), "orderNo", out.orderNo(),
			"name", plan.name(), "side", plan.side(), "quantity", plan.quantity(), "currency", plan.currency(),
			"orderType", plan.orderTypeLabel(), "price", plan.limitPrice() != null ? plan.limitPrice() : plan.refPrice());
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
		s.card("order_result", OrderCards.orderResult(out));
		s.summary(out.success() ? "취소 접수" : "취소 실패");
		return map("status", out.success() ? "CANCEL_SUBMITTED" : "FAILED", "message", out.message());
	}

	// ─────────────────────────── 자동 실행(예약·반복) ───────────────────────────

	@Tool(name = "schedule_task", description = "나중에 또는 반복해서 AI가 스스로 수행할 작업을 예약합니다. 1회 예약, 매일/요일 반복, 장 시작·마감 기준, 장중 반복을 모두 지원합니다. "
		+ "예약된 시각이 되면 서버가 AI(당신)를 깨워 instruction 을 읽고 도구로 수행하게 합니다. "
		+ "scheduleType: ONCE(runAt 필요, 예 2026-11-05T09:00) | DAILY(time=HH:mm, days=WEEKDAYS/EVERYDAY/MON,WED 등, market 을 주면 휴장일 제외) | "
		+ "MARKET_EVENT(market=KRX 또는 US, event=OPEN 또는 CLOSE, offsetMinutes=개장/마감 기준 분 단위 오프셋) | "
		+ "MARKET_INTERVAL(market, intervalMinutes>=5, 장중 반복). 시각은 한국 시간(KST) 기준이며 미국장은 market=US 로 주면 서머타임이 자동 반영됩니다. "
		+ "instruction 에는 사용자가 원하는 목표·조건·종목 수·금액·매도 기준을 빠짐없이 구체적으로 적으세요(실행할 때 이 글만 보고 일하기 때문입니다). "
		+ "주문이 포함된 작업이면 autonomous=true 와 budget(총 예산 시드)을 지정해야 하며, 실전 계좌는 사용자가 확인 카드에서 승인해야 시작됩니다.")
	public Object scheduleTask(
		@ToolParam(description = "작업 제목(짧게)") String title,
		@ToolParam(description = "실행할 때 AI가 읽고 수행할 구체적인 지시문") String instruction,
		@ToolParam(description = "ONCE, DAILY, MARKET_EVENT, MARKET_INTERVAL 중 하나") String scheduleType,
		@ToolParam(description = "ONCE 전용. 실행 시각 ISO 8601 (예: 2026-11-05T09:00)", required = false) String runAt,
		@ToolParam(description = "DAILY 전용. 실행 시각 HH:mm (24시간)", required = false) String time,
		@ToolParam(description = "반복 요일: WEEKDAYS, EVERYDAY, 또는 MON,WED,FRI 처럼. 생략하면 매일", required = false) String days,
		@ToolParam(description = "시장: KRX(한국) 또는 US(미국). 장 기준 일정이거나 휴장일을 건너뛰려면 지정", required = false) String market,
		@ToolParam(description = "MARKET_EVENT 전용. OPEN(개장) 또는 CLOSE(마감)", required = false) String event,
		@ToolParam(description = "MARKET_EVENT 전용. 기준 시각에서의 분 오프셋(음수=이전, 양수=이후). 예: 개장 10분 후는 10", required = false) Integer offsetMinutes,
		@ToolParam(description = "MARKET_INTERVAL 전용. 반복 간격(분, 5 이상)", required = false) Integer intervalMinutes,
		@ToolParam(description = "true 이면 이 작업이 사람 확인 없이 직접 주문할 수 있음. 주문하는 작업이면 반드시 true", required = false) Boolean autonomous,
		@ToolParam(description = "자율 주문의 총 예산(시드). 국내 종목은 원, 미국 종목은 달러. autonomous=true 이면 필수", required = false) Double budget,
		@ToolParam(description = "한 종목에 넣을 최대 비중(시드 대비 %). 기본 30", required = false) Double maxPositionPct,
		@ToolParam(description = "누적 손실이 시드의 이 %에 닿으면 신규 매수를 막음. 기본 15", required = false) Double maxLossPct,
		ToolContext ctx
	) {
		ChatSession s = session(ctx);
		MemberAccount account = s.account();
		Schedule.Type type;
		try {
			type = Schedule.Type.valueOf(scheduleType == null ? "" : scheduleType.trim().toUpperCase());
		} catch (IllegalArgumentException e) {
			throw ApiException.of(ErrorCode.AGENT_INVALID, "scheduleType 은 ONCE, DAILY, MARKET_EVENT, MARKET_INTERVAL 중 하나여야 합니다.");
		}
		Schedule schedule = new Schedule(type, runAt, time, Schedule.parseDays(days), null, market, event, offsetMinutes,
			intervalMinutes, null, null);
		schedule.validate();

		// 자동 실행 중인 AI 가 만드는 후속 작업: 한도와 장부는 부모를 그대로 따른다.
		if (s.agentTaskId() != null) {
			AgentTask parent = agentTasks.requireById(s.agentTaskId());
			AgentTask child = agentTasks.createChild(parent, account, title, instruction, schedule);
			s.card("agent_tasks", List.of(agentTasks.toDto(child)));
			s.summary("후속 작업 예약: " + schedule.describe());
			return map("status", "SCHEDULED", "taskId", child.getId(), "schedule", schedule.describe(),
				"note", "후속 작업이 등록되었습니다. 한도와 예산은 이 작업과 공유됩니다.");
		}

		boolean wantsAutonomy = Boolean.TRUE.equals(autonomous);
		Limits limits = null;
		if (budget != null && budget > 0) {
			Currency cur = schedule.marketEnum() != null && schedule.marketEnum().overseas() ? Currency.USD : Currency.KRW;
			limits = new Limits(cur.round(BigDecimal.valueOf(budget)), cur.name(), maxPositionPct, null, maxLossPct).normalized();
		}
		if (wantsAutonomy && limits == null && !account.isPaper()) {
			throw ApiException.of(ErrorCode.AGENT_INVALID,
				"실전 계좌에서 자동으로 주문하려면 총 예산(budget)이 필요합니다. 사용자에게 예산을 물어보세요.");
		}
		String planText = schedule.describe();

		if (!wantsAutonomy) {
			AgentTask t = agentTasks.create(new AgentTaskService.CreateSpec(account, s.conversationId(), null, title, instruction,
				null, schedule, limits, wantsAutonomy, null, AgentTask.CreatedBy.USER));
			s.card("agent_tasks", List.of(agentTasks.toDto(t)));
			s.summary("자동 실행 등록: " + planText);
			return map("status", "SCHEDULED", "taskId", t.getId(), "schedule", planText,
				"account", account.isPaper() ? "모의 계좌" : "실전 계좌",
				"note", "해당 시점에 정해진 AI가 지시 작업을 수행하고, 결과를 이 대화에 남깁니다. 이 작업은 주문하지 않으며 조회·보고만 합니다. 지시는 '자동화' 화면이나 update_agent_task 로 고칠 수 있습니다.");
		}		Action action = actionService.createAgentTaskAction(account, s.conversationId(), s.assistantMessageId(), title,
			instruction, schedule, limits);
		s.action(action);
		s.summary("확인 카드 생성: " + title);
		return map("status", "PENDING_USER_APPROVAL", "schedule", planText, "account", account.isPaper() ? "모의 계좌" : "실전 계좌",
			"message", "자동 실행은 아직 등록되지 않았습니다. 사용자가 확인 카드에서 승인해야 시작됩니다. 승인 전에는 등록되었다고 말하지 마세요.");
	}

	@Tool(name = "get_agent_task", description = "걸어 둔 자동 실행 한 건의 현재 '정리본'(지시 전문)과 일정·한도·최근 결과를 가져옵니다. 정리본을 고치기 전에 반드시 먼저 불러서 현재 내용을 확인하세요.")
	public Object getAgentTask(@ToolParam(description = "작업 번호(taskId)") Long taskId, ToolContext ctx) {
		ChatSession s = session(ctx);
		AgentTask t = agentTasks.require(s.memberId(), taskId);
		AgentTaskDto dto = agentTasks.toDto(t);
		s.card("agent_tasks", List.of(dto));
		s.summary("작업 " + taskId + " 조회");
		return map("taskId", t.getId(), "title", t.getTitle(), "status", dto.status(), "autonomous", t.isAutonomous(),
			"placesOrders", t.isAutonomous(), "schedule", dto.scheduleLabel(), "instruction", t.getInstruction(),
			"limits", dto.ledger() == null ? null : map("budget", dto.ledger().budget(), "maxPositionPct", dto.ledger().maxPositionPct(),
				"maxLossPct", dto.ledger().maxLossPct(), "maxDailyBuy", dto.ledger().maxDailyBuy()),
			"lastResult", dto.lastResult(), "fromSkill", t.getSkillId() != null);
	}

	@Tool(name = "update_agent_task", description = "걸어 둔 자동 실행의 정리본(지시)이나 제목을 고치거나 내용을 추가합니다. 일정·예산·한도는 바꾸지 않습니다(그것들은 새로 걸어야 합니다). "
		+ "mode=APPEND 는 기존 정리본 뒤에 '추가 지시'로 instruction 을 덧붙이고, mode=REPLACE 는 instruction 을 새 정리본 전체로 바꿉니다(이때는 get_agent_task 로 읽은 현재 정리본을 바탕으로 고친 전체를 쓰세요). "
		+ "주문하는(autonomous) 작업은 사용자가 변경 전후를 보는 확인 카드에서 승인해야 적용되고, 주문하지 않는 작업은 바로 적용됩니다. 승인 전에는 바뀌었다고 말하지 마세요.")
	public Object updateAgentTask(
		@ToolParam(description = "대상 작업 번호(taskId)") Long taskId,
		@ToolParam(description = "새 instruction. REPLACE 면 정리본 전체, APPEND 면 덧붙일 내용만", required = false) String instruction,
		@ToolParam(description = "REPLACE(기본) 또는 APPEND", required = false) String mode,
		@ToolParam(description = "새 제목. 안 바꾸면 생략", required = false) String title,
		ToolContext ctx
	) {
		ChatSession s = session(ctx);
		if (s.agentTaskId() != null) {
			throw ApiException.of(ErrorCode.AGENT_INVALID, "자동 실행 중에는 작업 지시를 고칠 수 없습니다.");
		}
		AgentTask t = agentTasks.requireRevisable(s.memberId(), taskId);
		MemberAccount account = accounts.requireOwned(s.memberId(), t.getAccountId());
		String merged = instruction == null || instruction.isBlank() ? t.getInstruction() : agentTasks.mergedInstruction(t, instruction, mode);
		if (merged != null && merged.equals(t.getInstruction()) && (title == null || title.isBlank() || title.equals(t.getTitle()))) {
			throw ApiException.of(ErrorCode.AGENT_INVALID, "바뀌는 내용이 없습니다.");
		}
		if (!t.isAutonomous()) {
			AgentTaskDto dto = agentTasks.applyRevision(s.memberId(), taskId, title, merged, "CHAT");
			s.card("agent_tasks", List.of(dto));
			s.summary("작업 " + taskId + " 지시 수정");
			return map("status", "UPDATED", "taskId", taskId, "note", "주문하지 않는 작업이라 바로 적용했습니다. 다음 실행부터 새 지시로 동작합니다.");
		}
		Action action = actionService.createAgentTaskUpdateAction(account, s.conversationId(), s.assistantMessageId(), t, title, merged);
		s.action(action);
		s.summary("정리본 수정 확인 카드: " + t.getTitle());
		return map("status", "PENDING_USER_APPROVAL", "taskId", taskId,
			"message", "아직 바뀌지 않았습니다. 변경 전후가 담긴 확인 카드에서 사용자가 승인해야 적용됩니다. 승인 전에는 수정되었다고 말하지 마세요.");
	}
	@Tool(name = "list_agent_tasks", description = "현재 계좌에 등록된 자동 실행(예약·반복) 작업 목록을 조회합니다.")
	public Object listAgentTasks(ToolContext ctx) {
		ChatSession s = session(ctx);
		List<AgentTaskDto> list = agentTasks.list(s.memberId(), s.account().getId(), null, null);
		s.card("agent_tasks", list);
		s.summary(list.size() + "건");
		return list.stream().limit(20).map(t -> map("taskId", t.id(), "title", t.title(), "status", t.status(),
			"schedule", t.scheduleLabel(), "nextRunAt", t.nextRunAt(), "lastResult", t.lastResult(), "skill", t.skillName())).toList();
	}

	@Tool(name = "manage_agent_task", description = "자동 실행 작업을 일시정지(PAUSE), 다시 시작(RESUME), 중지(CANCEL)합니다. 사용자가 명확히 요청했을 때만 사용하세요.")
	public Object manageAgentTask(
		@ToolParam(description = "대상 작업 번호(taskId)") Long taskId,
		@ToolParam(description = "PAUSE, RESUME, CANCEL 중 하나") String action,
		ToolContext ctx
	) {
		ChatSession s = session(ctx);
		String a = action == null ? "" : action.trim().toUpperCase();
		AgentTaskDto dto = switch (a) {
			case "PAUSE" -> agentTasks.pause(s.memberId(), taskId);
			case "RESUME" -> agentTasks.resume(s.memberId(), taskId);
			case "CANCEL" -> agentTasks.cancel(s.memberId(), taskId);
			default -> throw ApiException.of(ErrorCode.AGENT_INVALID, "action 은 PAUSE, RESUME, CANCEL 중 하나여야 합니다.");
		};
		s.card("agent_tasks", List.of(dto));
		s.summary("작업 " + taskId + " " + a);
		return map("status", dto.status(), "title", dto.title());
	}

	@Tool(name = "save_task_memory", description = "자동 실행 중에만 사용합니다. 다음 실행 때 참고할 내용(고른 종목, 기준가, 진행 상황 등)을 간결하게 저장합니다. 저장하면 이전 메모는 덮어써집니다.")
	public Object saveTaskMemory(@ToolParam(description = "저장할 메모. 2000자 이내로 핵심만") String memory, ToolContext ctx) {
		ChatSession s = session(ctx);
		if (s.agentTaskId() == null) {
			throw ApiException.of(ErrorCode.AGENT_INVALID, "작업 메모는 자동 실행 중에만 저장할 수 있습니다.");
		}
		String text = memory == null ? "" : memory.trim();
		if (text.length() > 2000) {
			text = text.substring(0, 2000);
		}
		agentTasks.saveMemory(s.agentTaskId(), text);
		s.summary("메모 저장");
		return map("status", "SAVED", "length", text.length());
	}

	@Tool(name = "list_skills", description = "사용자가 '스킬' 메뉴에서 켤 수 있는 자동매매 등 미리 만들어진 스킬 목록을 조회합니다. "
		+ "사용자가 정해진 전략의 자동 매매를 원하면 스킬 메뉴를 안내하세요.")
	public Object listSkills(ToolContext ctx) {
		ChatSession s = session(ctx);
		s.summary(skills.all().size() + "개 스킬");
		return skills.all().stream().map(k -> map("id", k.id(), "name", k.name(), "summary", k.summary(),
			"category", k.category(), "placesOrders", k.trades())).toList();
	}

	// ─────────────────────────── 이전 대화 ───────────────────────────

	@Tool(name = "search_chat_history", description = "사용자의 지난 대화(다른 대화 포함)에서 내용을 찾습니다. "
		+ "사용자가 '아까', '지난번에', '전에 말했던' 처럼 현재 대화 맥락에 없는 내용을 언급할 때 사용하세요. "
		+ "검색어를 비우면 최근 대화 목록을 돌려줍니다. 찾지 못하면 기억나지 않는다고 솔직하게 말하세요.")
	public Object searchChatHistory(
		@ToolParam(description = "찾을 키워드. 종목명, 금액, 주제 등. 비우면 최근 대화 목록", required = false) String query,
		ToolContext ctx
	) {
		ChatSession s = session(ctx);
		if (query == null || query.isBlank()) {
			var recent = historySearch.recent(s.memberId(), s.conversationId(), 5);
			s.summary("최근 대화 " + recent.size() + "개");
			return recent.stream().map(c -> map("conversationId", c.conversationId(), "title", c.title(),
				"lastMessage", c.lastMessagePreview(), "updatedAt", c.updatedAt())).toList();
		}
		var hits = historySearch.search(s.memberId(), s.conversationId(), query, 5);
		s.summary(hits.size() + "건 찾음");
		if (hits.isEmpty()) {
			return map("result", "일치하는 이전 대화가 없습니다.", "query", query);
		}
		return hits.stream().map(h -> map("conversationId", h.conversationId(), "conversationTitle", h.conversationTitle(),
			"role", h.role().equals("USER") ? "사용자" : "어시스턴트", "when", h.at(), "snippet", h.snippet())).toList();
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
