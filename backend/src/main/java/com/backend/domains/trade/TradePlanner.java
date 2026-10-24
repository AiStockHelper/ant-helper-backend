package com.backend.domains.trade;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;

import com.backend.common.exception.ApiException;
import com.backend.common.exception.ErrorCode;
import com.backend.common.properties.AppProperties;
import com.backend.domains.chat.dto.ChatDtos.Detail;
import com.backend.domains.member.domain.MemberAccount;
import com.backend.market.Currency;
import com.backend.market.Market;
import com.backend.market.MarketDataService;
import com.backend.market.TradingService;
import com.backend.market.dto.MarketDtos.OrderOutcome;
import com.backend.market.dto.MarketDtos.OrderRequest;
import com.backend.market.dto.MarketDtos.Quote;
import com.backend.market.dto.MarketDtos.StockSummary;

import lombok.RequiredArgsConstructor;

/**
 * 주문 계획 수립과 안전 검사. 채팅 도구/확인 카드 승인/예약 실행이 모두 이 규칙을 공유한다.
 */
@Service
@RequiredArgsConstructor
public class TradePlanner {

	public record Plan(
		Market market, String symbol, String name, String side, long quantity,
		BigDecimal limitPrice, BigDecimal refPrice, String orderTypeLabel
	) {
		public Currency currency() {
			return market.currency();
		}

		/** 예상 주문 금액 */
		public BigDecimal estimatedAmount() {
			BigDecimal unit = limitPrice != null ? limitPrice : refPrice;
			return unit.multiply(BigDecimal.valueOf(quantity));
		}

		public String sideLabel() {
			return "BUY".equals(side) ? "매수" : "매도";
		}
	}

	private final MarketDataService marketData;
	private final TradingService tradingService;
	private final AppProperties props;

	public static String normalizeSide(String side) {
		if (side == null) {
			throw ApiException.of(ErrorCode.ORDER_INVALID, "매수/매도 구분이 필요합니다.");
		}
		String s = side.trim().toUpperCase(Locale.ROOT);
		if (s.equals("BUY") || s.contains("매수") || s.equals("B")) {
			return "BUY";
		}
		if (s.equals("SELL") || s.contains("매도") || s.equals("S")) {
			return "SELL";
		}
		throw ApiException.of(ErrorCode.ORDER_INVALID, "매수/매도 구분을 알 수 없습니다: " + side);
	}

	/**
	 * 종목/수량 또는 금액/가격으로 주문 계획을 만든다.
	 * quantity 가 없으면 amount(해당 종목 통화 기준 금액)를 현재가로 나눈 몫을 수량으로 쓴다.
	 */
	public Plan plan(MemberAccount account, String stockRef, String marketHint, String side, Long quantity,
		BigDecimal amount, BigDecimal price) {
		String normalizedSide = normalizeSide(side);
		StockSummary stock = marketData.resolve(stockRef, marketHint == null || marketHint.isBlank() ? null : Market.parse(marketHint));
		marketData.requireEnabled(stock.market());
		Quote quote = marketData.quote(account, stock.market(), stock.productNumber());
		BigDecimal ref = quote.price();
		boolean sell = "SELL".equals(normalizedSide);

		BigDecimal limit = null;
		String typeLabel;
		if (price != null && price.signum() > 0) {
			limit = stock.market().overseas() ? price.setScale(2, RoundingMode.HALF_UP) : price.setScale(0, RoundingMode.HALF_UP);
			typeLabel = "지정가";
		} else if (stock.market().overseas()) {
			limit = tradingService.usMarketableLimit(ref, sell);
			typeLabel = "지정가 (현재가 " + props.getTrading().getUsLimitSlippagePct().stripTrailingZeros().toPlainString()
				+ "% " + (sell ? "아래" : "위") + ", 미국 주식은 시장가 주문이 불가능합니다)";
		} else {
			typeLabel = "시장가";
		}

		long qty;
		if (quantity != null && quantity > 0) {
			qty = quantity;
		} else if (amount != null && amount.signum() > 0) {
			BigDecimal unit = limit != null ? limit : ref;
			qty = amount.divide(unit, 0, RoundingMode.DOWN).longValue();
			if (qty < 1) {
				throw ApiException.of(ErrorCode.ORDER_INVALID,
					"금액 " + stock.currency().format(amount) + "으로는 " + stock.name() + " 1주("
						+ stock.currency().format(unit) + ")도 살 수 없습니다.");
			}
		} else {
			throw ApiException.of(ErrorCode.ORDER_INVALID, "수량 또는 금액을 알려주세요.");
		}
		return new Plan(stock.market(), stock.productNumber(), stock.name(), normalizedSide, qty, limit, ref, typeLabel);
	}

	/** 실전 계좌의 1회 주문 한도 검사. 모의 계좌는 제한하지 않는다. */
	public void enforceLimits(MemberAccount account, Plan plan) {
		if (account.isPaper()) {
			return;
		}
		BigDecimal estimated = plan.estimatedAmount();
		BigDecimal max = plan.currency() == Currency.KRW
			? BigDecimal.valueOf(props.getTrading().getMaxOrderAmountKrw())
			: BigDecimal.valueOf(props.getTrading().getMaxOrderAmountUsd());
		if (estimated.compareTo(max) > 0) {
			throw ApiException.of(ErrorCode.ORDER_LIMIT_EXCEEDED, "1회 주문 한도(" + plan.currency().format(max)
				+ ")를 초과합니다. 예상 주문 금액은 " + plan.currency().format(estimated) + "입니다.");
		}
	}

	public void requireMarketOpen(Market market) {
		if (props.getTrading().isSkipMarketHoursCheck()) {
			return;
		}
		if (!market.isOpenAt(ZonedDateTime.now())) {
			String hours = market == Market.KRX ? "한국 정규장(평일 09:00~15:30 KST)"
				: "미국 정규장(평일 09:30~16:00 뉴욕 현지 시간)";
			throw ApiException.of(ErrorCode.ORDER_INVALID,
				"지금은 " + hours + " 운영 시간이 아닙니다. 장이 열리는 시각으로 예약 주문을 걸 수 있습니다.");
		}
	}

	public OrderOutcome execute(MemberAccount account, Plan plan) {
		return tradingService.placeOrder(account,
			new OrderRequest(plan.market(), plan.symbol(), plan.side(), plan.quantity(), plan.limitPrice()));
	}

	/** 확인 카드에 보여줄 항목 */
	public List<Detail> details(MemberAccount account, Plan plan) {
		List<Detail> d = new ArrayList<>();
		d.add(new Detail("계좌", account.displayName() + " (" + (account.isPaper() ? "모의" : "실전") + ")"));
		d.add(new Detail("종목", plan.name() + " (" + plan.symbol() + ")"));
		d.add(new Detail("시장", plan.market().label()));
		d.add(new Detail("구분", plan.sideLabel()));
		d.add(new Detail("수량", String.format("%,d주", plan.quantity())));
		d.add(new Detail("주문 방식", plan.orderTypeLabel()));
		d.add(new Detail(plan.limitPrice() != null ? "주문 가격" : "현재가(참고)",
			plan.currency().format(plan.limitPrice() != null ? plan.limitPrice() : plan.refPrice())));
		d.add(new Detail("통화", plan.currency().name()));
		d.add(new Detail("예상 금액", plan.currency().format(plan.estimatedAmount())));
		return d;
	}
}
