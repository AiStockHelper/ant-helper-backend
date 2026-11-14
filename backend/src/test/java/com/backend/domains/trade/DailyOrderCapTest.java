package com.backend.domains.trade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.backend.common.exception.ApiException;
import com.backend.common.exception.ErrorCode;
import com.backend.common.properties.AppProperties;
import com.backend.domains.member.domain.MemberAccount;
import com.backend.domains.trade.TradePlanner.Plan;
import com.backend.market.Market;
import com.backend.order.kis.enums.AccountType;
import com.backend.support.InMemoryKeyValueStore;

class DailyOrderCapTest {

	private AppProperties props;
	private DailyOrderCap cap;

	@BeforeEach
	void setUp() {
		props = new AppProperties();
		props.getTrading().setDailyBuyCapKrw(1_000_000);
		props.getTrading().setDailyBuyCapUsd(500);
		props.getTrading().setDailyOrderCountCap(3);
		cap = new DailyOrderCap(new InMemoryKeyValueStore(), props);
	}

	private static MemberAccount account(long id, AccountType type) {
		MemberAccount a = MemberAccount.builder().memberId(1L).accountType(type).appKey("a").appKeySalt("v2").secretKey("s")
			.secretKeySalt("v2").accountNumber("12345678").accountProductCode("01").build();
		ReflectionTestUtils.setField(a, "id", id);
		return a;
	}

	private static Plan krwBuy(long qty, long price) {
		return new Plan(Market.KRX, "005930", "삼성전자", "BUY", qty, null, BigDecimal.valueOf(price), "시장가");
	}

	@Test
	void paperAccountsAreNotLimited() {
		MemberAccount paper = account(1, AccountType.PAPER_TRADE);
		for (int i = 0; i < 10; i++) {
			cap.reserve(paper, krwBuy(1000, 100_000));
		}
	}

	@Test
	void buyTotalIsCappedPerAccountPerDay() {
		MemberAccount real = account(1, AccountType.REAL_TRADE);
		cap.reserve(real, krwBuy(5, 100_000));           // 50만
		cap.reserve(real, krwBuy(4, 100_000));           // 90만
		assertThatThrownBy(() -> cap.reserve(real, krwBuy(2, 100_000)))   // 110만 > 100만
			.isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.DAILY_LIMIT_EXCEEDED));
		cap.reserve(real, krwBuy(1, 100_000));           // 거절된 주문은 사용량에 잡히지 않아 100만에 정확히 도달
		cap.release(real, krwBuy(1, 100_000));
		cap.reserve(real, krwBuy(1, 100_000));

		// 다른 계좌는 영향이 없다.
		cap.reserve(account(2, AccountType.REAL_TRADE), krwBuy(10, 100_000));
	}

	@Test
	void sellsOnlyCountTowardsTheOrderCount() {
		MemberAccount real = account(1, AccountType.REAL_TRADE);
		Plan sell = new Plan(Market.KRX, "005930", "삼성전자", "SELL", 100, null, BigDecimal.valueOf(1_000_000), "시장가");
		cap.reserve(real, sell);
		cap.reserve(real, sell);
		cap.reserve(real, sell);
		assertThatThrownBy(() -> cap.reserve(real, sell)).isInstanceOf(ApiException.class);
	}

	@Test
	void releasedOrdersFreeTheirSlot() {
		MemberAccount real = account(1, AccountType.REAL_TRADE);
		Plan small = krwBuy(1, 1000);
		cap.reserve(real, small);
		cap.reserve(real, small);
		cap.reserve(real, small);
		cap.release(real, small);
		cap.reserve(real, small);
		assertThatThrownBy(() -> cap.reserve(real, small)).isInstanceOf(ApiException.class);
	}

	@Test
	void usdUsesItsOwnCap() {
		MemberAccount real = account(1, AccountType.REAL_TRADE);
		Plan usd = new Plan(Market.NASDAQ, "AAPL", "Apple", "BUY", 2, BigDecimal.valueOf(200), BigDecimal.valueOf(200), "지정가");
		cap.reserve(real, usd);   // 400 USD
		assertThatThrownBy(() -> cap.reserve(real, usd)).isInstanceOf(ApiException.class);  // 800 > 500
	}
}
