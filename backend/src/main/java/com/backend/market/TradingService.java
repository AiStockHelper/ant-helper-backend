package com.backend.market;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.backend.common.dto.PageResponse;
import com.backend.common.exception.ApiException;
import com.backend.common.exception.ErrorCode;
import com.backend.common.properties.AppProperties;
import com.backend.domains.member.domain.MemberAccount;
import com.backend.market.dto.MarketDtos.AccountSummary;
import com.backend.market.dto.MarketDtos.Holding;
import com.backend.market.dto.MarketDtos.MarketSummary;
import com.backend.market.dto.MarketDtos.OrderHistoryItem;
import com.backend.market.dto.MarketDtos.OrderOutcome;
import com.backend.market.dto.MarketDtos.OrderRequest;
import com.backend.order.kis.kis_api.api.rest.overseas.OverseasTradingApis.BalanceApi;
import com.backend.order.kis.kis_api.api.rest.overseas.OverseasTradingApis.BalanceResult;
import com.backend.order.kis.kis_api.api.rest.overseas.OverseasTradingApis.CancelApi;
import com.backend.order.kis.kis_api.api.rest.overseas.OverseasTradingApis.CcnlApi;
import com.backend.order.kis.kis_api.api.rest.overseas.OverseasTradingApis.CcnlResult;
import com.backend.order.kis.kis_api.api.rest.overseas.OverseasTradingApis.OrderApi;
import com.backend.order.kis.kis_api.api.rest.overseas.OverseasTradingApis.OrderResult;
import com.backend.order.kis.kis_api.api.rest.overseas.OverseasTradingApis.PsAmountApi;
import com.backend.order.kis.kis_api.api.rest.overseas.OverseasTradingApis.PsAmountResult;
import com.backend.order.kis.kis_api.api.rest.trading.InquireBalanceApi;
import com.backend.order.kis.kis_api.api.rest.trading.InquireBalanceResult;
import com.backend.order.kis.kis_api.api.rest.trading.InquireDailyCcldApi;
import com.backend.order.kis.kis_api.api.rest.trading.InquireDailyCcldResult;
import com.backend.order.kis.kis_api.api.rest.trading.OrderCashApi;
import com.backend.order.kis.kis_api.api.rest.trading.OrderCashResult;
import com.backend.order.kis.kis_api.api.rest.trading.OrderRvsecnclApi;
import com.backend.order.kis.kis_api.api.rest.trading.OrderRvsecnclResult;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import static com.backend.market.MarketDataService.dec;

/**
 * 계좌 조회(잔고/체결내역)와 주문. 모든 KIS 호출은 {@link KisGateway} 를 거친다.
 * 이 클래스는 "누구의 계좌인가" 를 확인하지 않는다. 호출자가 소유 확인이 끝난 MemberAccount 만 넘겨야 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TradingService {

	private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyyMMdd");
	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	private final KisGateway kis;
	private final MarketDataService marketData;
	private final AppProperties props;

	// ─────────────────────────── 잔고 ───────────────────────────

	public AccountSummary summary(MemberAccount account) {
		MarketSummary domestic = domesticSummary(account);
		MarketSummary overseas = null;
		Double fx = null;
		if (marketData.enabledMarkets().stream().anyMatch(Market::overseas)) {
			try {
				BigDecimal[] cashAndRate = overseasCash(account);
				overseas = overseasSummary(account, cashAndRate[0]);
				fx = cashAndRate[1] == null || cashAndRate[1].signum() == 0 ? null : cashAndRate[1].doubleValue();
			} catch (RuntimeException e) {
				log.info("해외 잔고를 가져오지 못했습니다(계좌 {}): {}", account.getId(), e.getMessage());
			}
		}
		return new AccountSummary(account.getId(), Currency.KRW, domestic.cash(), domestic.totalPurchaseAmount(),
			domestic.totalEvaluationAmount(), domestic.totalAssets(), domestic.profitLoss(), domestic.profitLossRate(),
			domestic.holdings(), overseas, fx, OffsetDateTime.now(SEOUL));
	}

	private MarketSummary domesticSummary(MemberAccount account) {
		InquireBalanceApi api = new InquireBalanceApi();
		if (account.isPaper()) {
			api.setTrId("VTTC8434R");
		}
		InquireBalanceResult res = kis.execute(account, api);
		List<Holding> holdings = new ArrayList<>();
		if (res.getOutput1() != null) {
			for (InquireBalanceResult.Output1 o : res.getOutput1()) {
				long qty = dec(o.getHldgQty()).longValue();
				if (qty <= 0) {
					continue;
				}
				holdings.add(new Holding(o.getPdno(), o.getPrdtName(), Market.KRX, Currency.KRW, qty,
					dec(o.getOrdPsblQty()).longValue(), dec(o.getPchsAvgPric()).setScale(0, RoundingMode.HALF_UP),
					dec(o.getPrpr()), dec(o.getPchsAmt()), dec(o.getEvluAmt()), dec(o.getEvluPflsAmt()),
					MarketDataService.round2(dec(o.getEvluPflsRt()).doubleValue())));
			}
		}
		InquireBalanceResult.Output2 t = (res.getOutput2() != null && res.getOutput2().length > 0) ? res.getOutput2()[0] : null;
		BigDecimal cash = t == null ? BigDecimal.ZERO : dec(t.getDncaTotAmt());
		BigDecimal purchase = sum(holdings, Holding::purchaseAmount);
		BigDecimal evaluation = sum(holdings, Holding::evaluationAmount);
		BigDecimal pl = sum(holdings, Holding::profitLoss);
		BigDecimal totalAssets = t != null && dec(t.getTotEvluAmt()).signum() > 0 ? dec(t.getTotEvluAmt()) : cash.add(evaluation);
		return new MarketSummary(Currency.KRW, cash, purchase, evaluation, totalAssets, pl, rate(pl, purchase), holdings);
	}

	private MarketSummary overseasSummary(MemberAccount account, BigDecimal cash) {
		BalanceApi api = new BalanceApi(account.isPaper() ? "VTTS3012R" : "TTTS3012R");
		BalanceResult res = kis.execute(account, api);
		List<Holding> holdings = new ArrayList<>();
		if (res.getOutput1() != null) {
			for (BalanceResult.Position p : res.getOutput1()) {
				long qty = dec(p.getOvrsCblcQty()).longValue();
				if (qty <= 0) {
					continue;
				}
				Market market = marketOfOrderCode(p.getOvrsExcgCd());
				holdings.add(new Holding(p.getOvrsPdno(), p.getOvrsItemName(), market, Currency.USD, qty,
					dec(p.getOrdPsblQty()).longValue(), usd(p.getPchsAvgPric()), usd(p.getNowPric2()),
					usd(p.getFrcrPchsAmt1()), usd(p.getOvrsStckEvluAmt()), usd(p.getFrcrEvluPflsAmt()),
					MarketDataService.round2(dec(p.getEvluPflsRt()).doubleValue())));
			}
		}
		BigDecimal purchase = sum(holdings, Holding::purchaseAmount);
		BigDecimal evaluation = sum(holdings, Holding::evaluationAmount);
		BigDecimal pl = sum(holdings, Holding::profitLoss);
		return new MarketSummary(Currency.USD, Currency.USD.round(cash), purchase, evaluation,
			Currency.USD.round(cash.add(evaluation)), pl, rate(pl, purchase), holdings);
	}

	/** [주문가능 외화, 환율] */
	private BigDecimal[] overseasCash(MemberAccount account) {
		PsAmountApi api = new PsAmountApi(account.isPaper() ? "VTTS3007R" : "TTTS3007R", "NASD", "1", "AAPL");
		PsAmountResult.Output o = kis.execute(account, api).getOutput();
		if (o == null) {
			return new BigDecimal[] {BigDecimal.ZERO, null};
		}
		BigDecimal cash = dec(o.getFrcrOrdPsblAmt1()).signum() > 0 ? dec(o.getFrcrOrdPsblAmt1()) : dec(o.getOrdPsblFrcrAmt());
		return new BigDecimal[] {cash, dec(o.getExrt())};
	}

	/** 해외 매수 가능 수량(참고용). 실패하면 -1. */
	public long overseasBuyableQuantity(MemberAccount account, Market market, String symbol, BigDecimal price) {
		try {
			PsAmountApi api = new PsAmountApi(account.isPaper() ? "VTTS3007R" : "TTTS3007R", market.orderCode(),
				price.setScale(2, RoundingMode.HALF_UP).toPlainString(), symbol);
			PsAmountResult.Output o = kis.execute(account, api).getOutput();
			if (o == null) {
				return -1;
			}
			BigDecimal q = dec(o.getOvrsMaxOrdPsblQty()).signum() > 0 ? dec(o.getOvrsMaxOrdPsblQty()) : dec(o.getMaxOrdPsblQty());
			return q.longValue();
		} catch (RuntimeException e) {
			return -1;
		}
	}

	// ─────────────────────────── 주문/체결 내역 ───────────────────────────

	public PageResponse<OrderHistoryItem> orderHistory(MemberAccount account, boolean overseas, LocalDate from,
		LocalDate to, int page, int size) {
		List<OrderHistoryItem> all = overseas ? overseasOrders(account, from, to) : domesticOrders(account, from, to);
		all.sort(Comparator.comparing(OrderHistoryItem::orderedAt).reversed());
		int safeSize = Math.max(1, Math.min(size, 100));
		int start = Math.min(Math.max(page, 0) * safeSize, all.size());
		int end = Math.min(start + safeSize, all.size());
		Page<OrderHistoryItem> p = new PageImpl<>(all.subList(start, end), PageRequest.of(Math.max(page, 0), safeSize), all.size());
		return PageResponse.of(p);
	}

	private List<OrderHistoryItem> domesticOrders(MemberAccount account, LocalDate from, LocalDate to) {
		boolean recent = from.isAfter(LocalDate.now(SEOUL).minusMonths(3));
		InquireDailyCcldApi api = new InquireDailyCcldApi(from.format(YMD), to.format(YMD), "KRX");
		api.setTrId(account.isPaper() ? (recent ? "VTTC0081R" : "VTSC9215R") : (recent ? "TTTC0081R" : "CTSC9215R"));
		InquireDailyCcldResult res = kis.execute(account, api);
		List<OrderHistoryItem> out = new ArrayList<>();
		if (res.getOutput1() != null) {
			for (InquireDailyCcldResult.Output1 o : res.getOutput1()) {
				if (o == null || o.getOdno() == null || o.getOdno().isBlank()) {
					continue;
				}
				long ordQty = dec(o.getOrdQty()).longValue();
				long exeQty = dec(o.getTotCcldQty()).longValue();
				String status = "Y".equalsIgnoreCase(o.getCnclYn()) ? "CANCELED"
					: dec(o.getRjctQty()).signum() > 0 ? "REJECTED"
					: exeQty >= ordQty && ordQty > 0 ? "FILLED" : exeQty > 0 ? "PARTIAL" : "PENDING";
				out.add(new OrderHistoryItem(o.getOdno(), parseKst(o.getOrdDt(), o.getOrdTmd()),
					"01".equals(o.getSllBuyDvsnCd()) ? "SELL" : "BUY", o.getPdno(), o.getPrdtName(), Market.KRX,
					Currency.KRW, ordQty, dec(o.getOrdUnpr()), exeQty, dec(o.getAvgPrvs()).setScale(0, RoundingMode.HALF_UP),
					status));
			}
		}
		return out;
	}

	private List<OrderHistoryItem> overseasOrders(MemberAccount account, LocalDate from, LocalDate to) {
		CcnlApi api = new CcnlApi(account.isPaper() ? "VTTS3035R" : "TTTS3035R", from.format(YMD), to.format(YMD));
		if (account.isPaper()) {
			api.setOvrsExcgCd("%");
		}
		CcnlResult res = kis.execute(account, api);
		List<OrderHistoryItem> out = new ArrayList<>();
		if (res.getOutput() != null) {
			for (CcnlResult.Row o : res.getOutput()) {
				if (o == null || o.getOdno() == null || o.getOdno().isBlank()) {
					continue;
				}
				long ordQty = dec(o.getFtOrdQty()).longValue();
				long exeQty = dec(o.getFtCcldQty()).longValue();
				String stat = o.getPrcsStatName() == null ? "" : o.getPrcsStatName();
				String status = stat.contains("취소") ? "CANCELED"
					: stat.contains("거부") || (o.getRjctRson() != null && !o.getRjctRson().isBlank()) ? "REJECTED"
					: exeQty >= ordQty && ordQty > 0 ? "FILLED" : exeQty > 0 ? "PARTIAL" : "PENDING";
				Market m = marketOfOrderCode(o.getOvrsExcgCd());
				out.add(new OrderHistoryItem(o.getOdno(), parseKst(o.getOrdDt(), o.getOrdTmd()),
					"01".equals(o.getSllBuyDvsnCd()) ? "SELL" : "BUY", o.getPdno(), o.getPrdtName(), m, Currency.USD,
					ordQty, usd(o.getFtOrdUnpr3()), exeQty, usd(o.getFtCcldUnpr3()), status));
			}
		}
		return out;
	}

	// ─────────────────────────── 주문 ───────────────────────────

	public OrderOutcome placeOrder(MemberAccount account, OrderRequest req) {
		marketData.requireEnabled(req.market());
		if (req.quantity() <= 0) {
			throw ApiException.of(ErrorCode.ORDER_INVALID, "수량은 1주 이상이어야 합니다.");
		}
		boolean sell = "SELL".equalsIgnoreCase(req.side());
		String name = marketData.nameOf(req.market(), req.productNumber());
		try {
			if (req.market().overseas()) {
				if (req.limitPrice() == null || req.limitPrice().signum() <= 0) {
					throw ApiException.of(ErrorCode.ORDER_INVALID, "미국 주식은 지정가 주문만 가능합니다.");
				}
				String trId = account.isPaper() ? (sell ? "VTTT1001U" : "VTTT1002U") : (sell ? "TTTT1006U" : "TTTT1002U");
				String price = req.limitPrice().setScale(2, RoundingMode.HALF_UP).toPlainString();
				OrderResult res = kis.execute(account,
					new OrderApi(trId, req.market().orderCode(), req.productNumber(), String.valueOf(req.quantity()), price, sell));
				return outcome(true, "주문이 접수되었습니다.", res.getOutput() == null ? null : res.getOutput().getOdno(), req, name,
					req.limitPrice());
			}
			boolean market = req.limitPrice() == null;
			String price = market ? "0" : req.limitPrice().setScale(0, RoundingMode.HALF_UP).toPlainString();
			OrderCashApi api = new OrderCashApi(req.productNumber(), String.valueOf(req.quantity()), price, "KRX");
			api.setTrId(account.isPaper() ? (sell ? "VTTC0011U" : "VTTC0012U") : (sell ? "TTTC0011U" : "TTTC0012U"));
			api.setOrdDvsn(market ? "01" : "00");
			OrderCashResult res = kis.execute(account, api);
			return outcome(true, "주문이 접수되었습니다.", res.getOutput() == null ? null : res.getOutput().getOdno(), req, name,
				req.limitPrice());
		} catch (com.backend.order.kis.kis_client.exception.KisClientException e) {
			return outcome(false, e.getMessage(), null, req, name, req.limitPrice());
		}
	}

	private OrderOutcome outcome(boolean ok, String message, String orderNo, OrderRequest req, String name, BigDecimal price) {
		return new OrderOutcome(ok, message, orderNo, req.side().toUpperCase(Locale.ROOT), req.productNumber(), name,
			req.market(), req.market().currency(), req.quantity(), price);
	}

	/** 오늘 접수된 미체결 주문을 찾아 취소한다. */
	public OrderOutcome cancelOrder(MemberAccount account, Market market, String productNumber, String orderNo) {
		String name = marketData.nameOf(market, productNumber);
		LocalDate today = LocalDate.now(SEOUL);
		try {
			if (market.overseas()) {
				CcnlApi lookup = new CcnlApi(account.isPaper() ? "VTTS3035R" : "TTTS3035R", today.minusDays(1).format(YMD), today.format(YMD));
				lookup.setCcldNccsDvsn("02");
				CcnlResult found = kis.execute(account, lookup);
				CcnlResult.Row row = found.getOutput() == null ? null : java.util.Arrays.stream(found.getOutput())
					.filter(r -> r != null && orderNo.equals(r.getOdno())).findFirst().orElse(null);
				if (row == null) {
					return new OrderOutcome(false, "취소할 수 있는 미체결 주문을 찾지 못했습니다.", orderNo, "", productNumber, name,
						market, market.currency(), 0, null);
				}
				CancelApi api = new CancelApi(account.isPaper() ? "VTTT1004U" : "TTTT1004U",
					row.getOvrsExcgCd() == null || row.getOvrsExcgCd().isBlank() ? market.orderCode() : row.getOvrsExcgCd(),
					productNumber, orderNo, String.valueOf(dec(row.getNccsQty()).longValue()));
				kis.execute(account, api);
				return new OrderOutcome(true, "주문 취소가 접수되었습니다.", orderNo, "", productNumber, name, market, market.currency(),
					dec(row.getNccsQty()).longValue(), null);
			}
			InquireDailyCcldApi lookup = new InquireDailyCcldApi(today.format(YMD), today.format(YMD), "KRX");
			lookup.setTrId(account.isPaper() ? "VTTC0081R" : "TTTC0081R");
			lookup.setCcldDvsn("02");
			InquireDailyCcldResult found = kis.execute(account, lookup);
			InquireDailyCcldResult.Output1 row = found.getOutput1() == null ? null : java.util.Arrays.stream(found.getOutput1())
				.filter(r -> r != null && orderNo.equals(r.getOdno())).findFirst().orElse(null);
			if (row == null) {
				return new OrderOutcome(false, "취소할 수 있는 미체결 주문을 찾지 못했습니다.", orderNo, "", productNumber, name,
					market, market.currency(), 0, null);
			}
			OrderRvsecnclApi api = new OrderRvsecnclApi(row.getOrdGnoBrno(), orderNo);
			api.setTrId(account.isPaper() ? "VTTC0013U" : "TTTC0013U");
			api.setRvseCnclDvsnCd("02");
			api.setQtyAllOrdYn("Y");
			OrderRvsecnclResult res = kis.execute(account, api);
			return new OrderOutcome(true, "주문 취소가 접수되었습니다.", res.getOutput() == null ? orderNo : res.getOutput().getOdno(),
				"", productNumber, name, market, market.currency(), dec(row.getRmnQty()).longValue(), null);
		} catch (com.backend.order.kis.kis_client.exception.KisClientException e) {
			return new OrderOutcome(false, e.getMessage(), orderNo, "", productNumber, name, market, market.currency(), 0, null);
		}
	}

	/** 미국 지정가: 현재가 기준 ±slippage% 로 체결 가능성을 높인다. */
	public BigDecimal usMarketableLimit(BigDecimal lastPrice, boolean sell) {
		BigDecimal pct = props.getTrading().getUsLimitSlippagePct().divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
		BigDecimal factor = sell ? BigDecimal.ONE.subtract(pct) : BigDecimal.ONE.add(pct);
		return lastPrice.multiply(factor).setScale(2, RoundingMode.HALF_UP);
	}

	// ─────────────────────────── 유틸 ───────────────────────────

	private static Market marketOfOrderCode(String code) {
		if (code == null) {
			return Market.NASDAQ;
		}
		return switch (code.trim().toUpperCase(Locale.ROOT)) {
			case "NYSE", "NYS" -> Market.NYSE;
			case "AMEX", "AMS" -> Market.AMEX;
			default -> Market.NASDAQ;
		};
	}

	private static BigDecimal usd(String s) {
		return Currency.USD.round(dec(s));
	}

	private static double rate(BigDecimal pl, BigDecimal purchase) {
		if (purchase == null || purchase.signum() == 0) {
			return 0;
		}
		return MarketDataService.round2(pl.doubleValue() / purchase.doubleValue() * 100);
	}

	private static <T> BigDecimal sum(List<T> list, java.util.function.Function<T, BigDecimal> f) {
		BigDecimal s = BigDecimal.ZERO;
		for (T t : list) {
			s = s.add(f.apply(t));
		}
		return s;
	}

	private static OffsetDateTime parseKst(String yyyymmdd, String hhmmss) {
		try {
			LocalDate d = LocalDate.parse(yyyymmdd, YMD);
			String t = hhmmss == null ? "000000" : String.format("%6s", hhmmss).replace(' ', '0');
			LocalTime time = LocalTime.of(Integer.parseInt(t.substring(0, 2)), Integer.parseInt(t.substring(2, 4)),
				Integer.parseInt(t.substring(4, 6)));
			return LocalDateTime.of(d, time).atZone(SEOUL).toOffsetDateTime();
		} catch (RuntimeException e) {
			return OffsetDateTime.now(SEOUL);
		}
	}
}
