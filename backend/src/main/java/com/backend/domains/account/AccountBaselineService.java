package com.backend.domains.account;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneId;
import java.time.OffsetDateTime;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.backend.common.exception.ApiException;
import com.backend.common.exception.ErrorCode;
import com.backend.domains.account.AccountService.AccountDeletedEvent;
import com.backend.market.Currency;
import com.backend.market.dto.MarketDtos.AccountSummary;
import com.backend.market.dto.MarketDtos.BaselineInfo;
import com.backend.market.dto.MarketDtos.MarketSummary;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 기준 자산(원금) 대비 손익을 계산해 계좌 요약에 붙인다.
 * 기준이 없으면 지금 총자산을 기준으로 자동 기록한다. 기준 자산 저장소에 문제가 생겨도 계좌 요약은 그대로 나가야 하므로
 * 이 서비스의 오류는 요약에 영향을 주지 않는다(기준 손익만 빠진다).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountBaselineService {

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	private final AccountBaselineRepository repository;

	/** 국내(원화)와 해외(달러) 요약에 각각 기준 자산 대비 손익을 붙인다. */
	public AccountSummary enrich(AccountSummary summary) {
		try {
			BaselineInfo domestic = info(summary.accountId(), Currency.KRW, summary.totalAssets());
			MarketSummary overseas = summary.overseas();
			if (overseas != null) {
				overseas = overseas.withBaseline(info(summary.accountId(), Currency.USD, overseas.totalAssets()));
			}
			return summary.withBaselines(domestic, overseas);
		} catch (RuntimeException e) {
			log.warn("기준 자산 손익을 계산하지 못했습니다(계좌 {}): {}", summary.accountId(), e.toString());
			return summary;
		}
	}

	BaselineInfo info(Long accountId, Currency currency, BigDecimal totalAssets) {
		if (totalAssets == null) {
			return null;
		}
		AccountBaseline b = repository.findByAccountIdAndCurrency(accountId, currency.name()).orElse(null);
		if (b == null) {
			if (totalAssets.signum() <= 0) {
				return null;                       // 자산이 아직 없으면 기준을 만들지 않는다
			}
			b = createAuto(accountId, currency, totalAssets);
		}
		return toInfo(b, currency, totalAssets);
	}

	private AccountBaseline createAuto(Long accountId, Currency currency, BigDecimal totalAssets) {
		try {
			return repository.save(new AccountBaseline(accountId, currency.name(), currency.round(totalAssets), false));
		} catch (DataIntegrityViolationException e) {
			// 동시에 처음 조회한 다른 요청이 먼저 만들었다
			return repository.findByAccountIdAndCurrency(accountId, currency.name()).orElseThrow(() -> e);
		}
	}

	static BaselineInfo toInfo(AccountBaseline b, Currency currency, BigDecimal totalAssets) {
		BigDecimal pnl = currency.round(totalAssets.subtract(b.getAmount()));
		double rate = b.getAmount().signum() <= 0 ? 0
			: pnl.divide(b.getAmount(), 6, RoundingMode.HALF_UP).doubleValue() * 100;
		return new BaselineInfo(b.getAmount(), OffsetDateTime.ofInstant(b.getBaselineAt(), SEOUL), b.isManual(), pnl,
			Math.round(rate * 100) / 100.0);
	}

	/** 기준 자산을 직접 정한다. */
	@Transactional
	public void set(Long accountId, Currency currency, BigDecimal amount) {
		if (amount == null || amount.signum() <= 0) {
			throw ApiException.of(ErrorCode.INVALID_PARAMETER, "기준 자산은 0보다 커야 합니다.");
		}
		BigDecimal value = currency.round(amount);
		AccountBaseline b = repository.findByAccountIdAndCurrency(accountId, currency.name()).orElse(null);
		if (b == null) {
			repository.save(new AccountBaseline(accountId, currency.name(), value, true));
		} else {
			b.setManually(value);
		}
	}

	/** 기준 자산을 지운다. 다음에 계좌를 조회할 때 그 시점의 총자산이 새 기준이 된다. */
	@Transactional
	public void reset(Long accountId, Currency currency) {
		repository.deleteByAccountIdAndCurrency(accountId, currency.name());
	}

	/** 계좌를 삭제하면 그 계좌의 기준 자산도 지운다(삭제가 끝난 뒤 따로 처리해서, 여기서 실패해도 계좌 삭제에는 영향이 없다). */
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void onAccountDeleted(AccountDeletedEvent event) {
		try {
			repository.deleteByAccountId(event.accountId());
		} catch (RuntimeException e) {
			log.warn("삭제된 계좌의 기준 자산을 지우지 못했습니다(계좌 {}): {}", event.accountId(), e.toString());
		}
	}
}
