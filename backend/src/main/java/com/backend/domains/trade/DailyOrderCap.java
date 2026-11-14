package com.backend.domains.trade;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;

import org.springframework.stereotype.Component;

import com.backend.common.exception.ApiException;
import com.backend.common.exception.ErrorCode;
import com.backend.common.properties.AppProperties;
import com.backend.common.security.guard.KeyValueStore;
import com.backend.domains.member.domain.MemberAccount;
import com.backend.domains.trade.TradePlanner.Plan;
import com.backend.market.Currency;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 실전 계좌 한 곳이 하루(한국시간)에 낼 수 있는 주문 횟수와 매수 총액의 상한.
 * 1회 한도·작업별 한도와 별개로, 토큰이 탈취되거나 자동 실행이 폭주해도 하루 피해가 이 한도 안에서 멈추게 한다.
 * 주문을 보내기 직전에 예약하고, 주문이 접수되지 못하면 되돌린다. 모의 계좌에는 적용하지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DailyOrderCap {

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
	private static final Duration TTL = Duration.ofHours(36);

	private final KeyValueStore store;
	private final AppProperties props;

	/** 한도를 넘으면 예외. 통과하면 사용량에 반영한다. */
	public void reserve(MemberAccount account, Plan plan) {
		if (account.isPaper()) {
			return;
		}
		AppProperties.Trading t = props.getTrading();
		String day = LocalDate.now(SEOUL).toString();
		String countKey = "cap:" + account.getId() + ":" + day + ":count";

		long count = store.add(countKey, 1, TTL);
		if (count > t.getDailyOrderCountCap()) {
			store.add(countKey, -1, TTL);
			throw ApiException.of(ErrorCode.DAILY_LIMIT_EXCEEDED,
				"이 계좌의 하루 주문 횟수 한도(" + t.getDailyOrderCountCap() + "회)에 도달했습니다. 내일 다시 시도해 주세요.");
		}
		if ("BUY".equalsIgnoreCase(plan.side())) {
			Currency cur = plan.currency();
			long cap = cur == Currency.KRW ? t.getDailyBuyCapKrw() : t.getDailyBuyCapUsd();
			long amount = amountOf(plan);
			String buyKey = "cap:" + account.getId() + ":" + day + ":buy:" + cur;
			long total = store.add(buyKey, amount, TTL);
			if (total > cap) {
				store.add(buyKey, -amount, TTL);
				store.add(countKey, -1, TTL);
				long used = total - amount;
				throw ApiException.of(ErrorCode.DAILY_LIMIT_EXCEEDED,
					"이 계좌의 하루 매수 한도(" + cur.format(BigDecimal.valueOf(cap)) + ")를 넘어 주문할 수 없습니다. 오늘 이미 "
						+ cur.format(BigDecimal.valueOf(used)) + " 매수 주문을 냈습니다.");
			}
		}
	}

	/** 주문이 접수되지 못했을 때 예약을 되돌린다. */
	public void release(MemberAccount account, Plan plan) {
		if (account.isPaper()) {
			return;
		}
		String day = LocalDate.now(SEOUL).toString();
		store.add("cap:" + account.getId() + ":" + day + ":count", -1, TTL);
		if ("BUY".equalsIgnoreCase(plan.side())) {
			store.add("cap:" + account.getId() + ":" + day + ":buy:" + plan.currency(), -amountOf(plan), TTL);
		}
	}

	private static long amountOf(Plan plan) {
		return plan.estimatedAmount().setScale(0, RoundingMode.CEILING).longValue();
	}
}
