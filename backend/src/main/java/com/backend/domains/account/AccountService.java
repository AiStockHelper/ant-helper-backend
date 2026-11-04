package com.backend.domains.account;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import javax.crypto.SecretKey;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backend.common.dto.PageResponse;
import com.backend.common.exception.ApiException;
import com.backend.common.exception.ErrorCode;
import com.backend.common.util.encoder.EncryptUtil;
import com.backend.domains.account.AccountDtos.Account;
import com.backend.domains.account.AccountDtos.AccountDetail;
import com.backend.domains.account.AccountDtos.CreateAccountRequest;
import com.backend.domains.account.AccountDtos.UpdateAccountRequest;
import com.backend.domains.member.domain.MemberAccount;
import com.backend.domains.member.repository.MemberAccountRepository;
import com.backend.market.KisGateway;
import com.backend.market.TradingService;
import com.backend.market.dto.MarketDtos.AccountSummary;
import com.backend.market.dto.MarketDtos.OrderHistoryItem;
import com.backend.order.kis.kis_api.api.rest.trading.InquireBalanceApi;
import com.backend.order.kis.kis_client.KisClient;
import com.backend.order.kis.kis_client.exception.KisClientException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class AccountService {

	public record AccountDeletedEvent(Long memberId, Long accountId) {
	}

	private final MemberAccountRepository repository;
	private final KisGateway kis;
	private final TradingService tradingService;
	private final ApplicationEventPublisher events;

	/** 소유권을 확인해서 계좌를 돌려준다. 다른 사람의 계좌 ID 를 넣으면 존재하지 않는 것처럼 응답한다. */
	@Transactional(readOnly = true)
	public MemberAccount requireOwned(Long memberId, Long accountId) {
		return repository.findByIdAndMemberId(accountId, memberId)
			.orElseThrow(() -> ApiException.from(ErrorCode.MEMBER_ACCOUNT_NOT_FOUND));
	}

	/** 소유자 검사 없이 계좌를 가져온다. 운영자가 설정한 시스템 계좌(MCP 시세 조회용)에만 사용한다. */
	@Transactional(readOnly = true)
	public MemberAccount getSystemAccount(Long accountId) {
		return repository.findById(accountId).orElseThrow(() -> ApiException.from(ErrorCode.MEMBER_ACCOUNT_NOT_FOUND));
	}

	/** accountId 가 있으면 그 계좌, 없으면 기본 계좌, 그것도 없으면 가장 먼저 등록한 계좌. 하나도 없으면 예외. */
	@Transactional(readOnly = true)
	public MemberAccount resolve(Long memberId, Long accountId) {
		if (accountId != null) {
			return requireOwned(memberId, accountId);
		}
		return findDefault(memberId).orElseThrow(() -> ApiException.from(ErrorCode.NO_ACCOUNT_SELECTED));
	}

	/**
	 * 기본 계좌(없으면 가장 먼저 등록한 계좌)를 예외 없이 찾는다.
	 * 호출자가 계좌 없음을 정상 상황으로 처리할 때 쓴다. 트랜잭션 안에서 예외를 던졌다 잡으면
	 * 바깥 트랜잭션이 rollback-only 로 표시되어 UnexpectedRollbackException 이 나기 때문이다.
	 */
	@Transactional(readOnly = true)
	public java.util.Optional<MemberAccount> findDefault(Long memberId) {
		return repository.findFirstByMemberIdAndIsDefaultTrue(memberId)
			.or(() -> repository.findAllByMemberIdOrderByIdAsc(memberId).stream().findFirst());
	}

	@Transactional(readOnly = true)
	public List<Account> list(Long memberId) {
		return repository.findAllByMemberIdOrderByIdAsc(memberId).stream().map(Account::from).toList();
	}

	@Transactional(readOnly = true)
	public AccountDetail detail(Long memberId, Long accountId) {
		MemberAccount a = requireOwned(memberId, accountId);
		return new AccountDetail(a.getId(), a.getAccountType(), a.displayName(), a.maskedAccountNumber(),
			a.getAccountProductCode(), a.isDefaultAccount(), com.backend.common.util.TimeUtil.kst(a.getCreatedAt()),
			a.isPaper() ? "VIRTUAL" : "REAL", "UNKNOWN");
	}

	@Transactional
	public Account create(Long memberId, CreateAccountRequest req) {
		if (repository.existsByMemberIdAndAccountNumberAndAccountType(memberId, req.accountNumber(), req.accountType())) {
			log.warn("계좌 등록 거절(중복) member={} type={}", memberId, req.accountType());
			throw ApiException.from(ErrorCode.DUPLICATE_MEMBER_ACCOUNT);
		}
		validateWithKis(req);

		SecretKey appKeySalt = EncryptUtil.generateKey();
		SecretKey secretKeySalt = EncryptUtil.generateKey();
		boolean first = repository.findAllByMemberId(memberId).isEmpty();
		MemberAccount account = MemberAccount.builder()
			.memberId(memberId)
			.accountType(req.accountType())
			.appKey(EncryptUtil.encrypt(req.appKey().trim(), appKeySalt))
			.appKeySalt(EncryptUtil.keyToString(appKeySalt))
			.secretKey(EncryptUtil.encrypt(req.secretKey().trim(), secretKeySalt))
			.secretKeySalt(EncryptUtil.keyToString(secretKeySalt))
			.accountNumber(req.accountNumber())
			.accountProductCode(req.accountProductCode())
			.alias(req.alias() == null || req.alias().isBlank() ? null : req.alias().trim())
			.isDefault(first)
			.build();
		MemberAccount saved = repository.save(account);
		log.info("계좌 등록 member={} account={} type={} default={}", memberId, saved.getId(), req.accountType(), first);
		return Account.from(saved);
	}

	@Transactional
	public Account update(Long memberId, Long accountId, UpdateAccountRequest req) {
		MemberAccount a = requireOwned(memberId, accountId);
		if (req.alias() != null) {
			a.rename(req.alias());
		}
		if (Boolean.TRUE.equals(req.isDefault())) {
			repository.findAllByMemberId(memberId).forEach(o -> o.markDefault(false));
			a.markDefault(true);
		}
		return Account.from(a);
	}

	@Transactional
	public void delete(Long memberId, Long accountId) {
		MemberAccount a = requireOwned(memberId, accountId);
		boolean wasDefault = a.isDefaultAccount();
		repository.delete(a);
		log.info("계좌 삭제 member={} account={} wasDefault={}", memberId, accountId, wasDefault);
		kis.evict(accountId);
		events.publishEvent(new AccountDeletedEvent(memberId, accountId));
		if (wasDefault) {
			repository.findAllByMemberIdOrderByIdAsc(memberId).stream().findFirst().ifPresent(n -> n.markDefault(true));
		}
	}

	public AccountSummary summary(Long memberId, Long accountId) {
		return tradingService.summary(requireOwned(memberId, accountId));
	}

	public PageResponse<OrderHistoryItem> orders(Long memberId, Long accountId, boolean overseas, LocalDate from,
		LocalDate to, int page, int size) {
		LocalDate end = to != null ? to : LocalDate.now(ZoneId.of("Asia/Seoul"));
		LocalDate start = from != null ? from : end.minusDays(30);
		if (start.isAfter(end)) {
			throw ApiException.from(ErrorCode.INVALID_PARAMETER);
		}
		return tradingService.orderHistory(requireOwned(memberId, accountId), overseas, start, end, page, size);
	}

	/** 저장 전에 입력한 키로 잔고를 조회해 본다(키/계좌번호/모의·실전 구분 확인). */
	private void validateWithKis(CreateAccountRequest req) {
		boolean paper = req.accountType() == com.backend.order.kis.enums.AccountType.PAPER_TRADE;
		KisClient client = kis.temporaryClient(req.appKey().trim(), req.secretKey().trim(), req.accountNumber(),
			req.accountProductCode(), paper);
		try {
			InquireBalanceApi api = new InquireBalanceApi();
			if (paper) {
				api.setTrId("VTTC8434R");
			}
			kis.execute(client, api);
		} catch (KisClientException e) {
			log.warn("계좌 검증 실패 type={}: {}", req.accountType(), e.getMessage());
			throw ApiException.of(ErrorCode.KIS_ACCOUNT_NOT_FOUND,
				"한국투자증권에서 계좌를 확인하지 못했습니다: " + e.getMessage()
					+ " (모의/실전 구분과 앱키·시크릿·계좌번호를 확인해 주세요.)");
		} finally {
			try {
				client.close();
			} catch (Exception ignored) {
				// 임시 클라이언트 정리 실패는 무시
			}
		}
	}
}
