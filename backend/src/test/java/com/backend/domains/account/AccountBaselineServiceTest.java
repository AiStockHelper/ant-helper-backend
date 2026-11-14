package com.backend.domains.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import com.backend.common.exception.ApiException;
import com.backend.market.Currency;
import com.backend.market.dto.MarketDtos.AccountSummary;
import com.backend.market.dto.MarketDtos.BaselineInfo;
import com.backend.market.dto.MarketDtos.MarketSummary;

class AccountBaselineServiceTest {

	private AccountBaselineRepository repo;
	private AccountBaselineService service;

	@BeforeEach
	void setUp() {
		repo = mock(AccountBaselineRepository.class);
		service = new AccountBaselineService(repo);
		when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
	}

	private static AccountSummary summary(String krwAssets, String usdAssets) {
		MarketSummary os = usdAssets == null ? null : new MarketSummary(Currency.USD, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
			new BigDecimal(usdAssets), BigDecimal.ZERO, 0, List.of());
		return new AccountSummary(7L, Currency.KRW, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal(krwAssets), BigDecimal.ZERO, 0,
			List.of(), os, 1400.0, OffsetDateTime.now());
	}

	@Test
	void firstLookRecordsTheCurrentAssetsAsTheAutomaticBaselineWithZeroProfit() {
		when(repo.findByAccountIdAndCurrency(7L, "KRW")).thenReturn(Optional.empty());
		AccountSummary out = service.enrich(summary("10000000", null));
		ArgumentCaptor<AccountBaseline> saved = ArgumentCaptor.forClass(AccountBaseline.class);
		verify(repo).save(saved.capture());
		assertThat(saved.getValue().getAmount()).isEqualByComparingTo("10000000");
		assertThat(saved.getValue().isManual()).isFalse();
		assertThat(out.baseline().amount()).isEqualByComparingTo("10000000");
		assertThat(out.baseline().pnl()).isEqualByComparingTo("0");
		assertThat(out.baseline().pnlRate()).isZero();
		assertThat(out.baseline().manual()).isFalse();
		assertThat(out.overseas()).isNull();
	}

	@Test
	void profitAndRateAreMeasuredAgainstTheBaselineForBothCurrencies() {
		when(repo.findByAccountIdAndCurrency(7L, "KRW")).thenReturn(Optional.of(new AccountBaseline(7L, "KRW", new BigDecimal("50000000"), true)));
		when(repo.findByAccountIdAndCurrency(7L, "USD")).thenReturn(Optional.of(new AccountBaseline(7L, "USD", new BigDecimal("3000.00"), false)));
		AccountSummary out = service.enrich(summary("51234567", "2850.50"));
		assertThat(out.baseline().pnl()).isEqualByComparingTo("1234567");
		assertThat(out.baseline().pnlRate()).isEqualTo(2.47);                 // 1,234,567 ÷ 50,000,000
		assertThat(out.baseline().manual()).isTrue();
		BaselineInfo usd = out.overseas().baseline();
		assertThat(usd.pnl()).isEqualByComparingTo("-149.50");
		assertThat(usd.pnlRate()).isEqualTo(-4.98);
		verify(repo, never()).save(any());
	}

	@Test
	void noBaselineIsCreatedWhileTheAccountHasNoAssets() {
		when(repo.findByAccountIdAndCurrency(anyLong(), anyString())).thenReturn(Optional.empty());
		AccountSummary out = service.enrich(summary("0", "0"));
		verify(repo, never()).save(any());
		assertThat(out.baseline()).isNull();
		assertThat(out.overseas().baseline()).isNull();
	}

	private static long anyLong() {
		return org.mockito.ArgumentMatchers.anyLong();
	}

	@Test
	void ifTwoRequestsRaceToCreateTheBaselineTheLoserReadsTheWinnersRow() {
		AccountBaseline winner = new AccountBaseline(7L, "KRW", new BigDecimal("9000000"), false);
		when(repo.findByAccountIdAndCurrency(7L, "KRW")).thenReturn(Optional.empty(), Optional.of(winner));
		when(repo.save(any())).thenThrow(new DataIntegrityViolationException("duplicate"));
		AccountSummary out = service.enrich(summary("10000000", null));
		assertThat(out.baseline().amount()).isEqualByComparingTo("9000000");
		assertThat(out.baseline().pnl()).isEqualByComparingTo("1000000");
	}

	@Test
	void aBrokenBaselineStoreNeverBreaksTheAccountSummary() {
		when(repo.findByAccountIdAndCurrency(anyLong(), anyString())).thenThrow(new IllegalStateException("table missing"));
		AccountSummary in = summary("10000000", "100");
		AccountSummary out = service.enrich(in);
		assertThat(out).isSameAs(in);
		assertThat(out.baseline()).isNull();
	}

	@Test
	void settingAndResettingTheBaseline() {
		when(repo.findByAccountIdAndCurrency(7L, "KRW")).thenReturn(Optional.empty());
		service.set(7L, Currency.KRW, new BigDecimal("30000000.7"));
		ArgumentCaptor<AccountBaseline> saved = ArgumentCaptor.forClass(AccountBaseline.class);
		verify(repo).save(saved.capture());
		assertThat(saved.getValue().isManual()).isTrue();
		assertThat(saved.getValue().getAmount()).isEqualByComparingTo("30000001");           // 원화는 원 단위로

		AccountBaseline existing = new AccountBaseline(7L, "USD", new BigDecimal("1000"), false);
		when(repo.findByAccountIdAndCurrency(7L, "USD")).thenReturn(Optional.of(existing));
		service.set(7L, Currency.USD, new BigDecimal("2500.126"));
		assertThat(existing.getAmount()).isEqualByComparingTo("2500.13");
		assertThat(existing.isManual()).isTrue();

		service.reset(7L, Currency.KRW);
		verify(repo).deleteByAccountIdAndCurrency(7L, "KRW");
		assertThatThrownBy(() -> service.set(7L, Currency.KRW, BigDecimal.ZERO)).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> service.set(7L, Currency.KRW, null)).isInstanceOf(ApiException.class);
	}

	@Test
	void deletingAnAccountRemovesItsBaselinesButNeverThrows() {
		service.onAccountDeleted(new AccountService.AccountDeletedEvent(1L, 7L));
		verify(repo).deleteByAccountId(7L);
		org.mockito.Mockito.doThrow(new IllegalStateException("x")).when(repo).deleteByAccountId(8L);
		service.onAccountDeleted(new AccountService.AccountDeletedEvent(1L, 8L));
	}
}