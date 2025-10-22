package com.backend.order.domestic.service;

import static com.backend.common.exception.ErrorCode.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backend.common.exception.ApiException;
import com.backend.common.exception.ErrorCode;
import com.backend.domains.member.domain.MemberAccount;
import com.backend.domains.member.repository.MemberAccountRepository;
import com.backend.order.domestic.dto.request.DomesticTradeRequest;
import com.backend.order.kis.enums.AccountType;
import com.backend.order.kis.kis_api.api.rest.quotations.InquirePriceApi;
import com.backend.order.kis.kis_api.api.rest.quotations.InquirePriceResult;
import com.backend.order.kis.kis_api.api.rest.trading.InquireBalanceApi;
import com.backend.order.kis.kis_api.api.rest.trading.InquireBalanceResult;
import com.backend.order.kis.kis_api.api.rest.trading.OrderCashApi;
import com.backend.order.kis.kis_api.api.rest.trading.OrderCashResult;
import com.backend.order.kis.kis_client.KisClient;
import com.backend.order.kis.kis_client.config.Configuration;
import com.backend.order.kis.kis_client.config.Credentials;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DomesticStockService {

	private final MemberAccountRepository memberAccountRepository;

	// 국내주식 가격 조회
	public InquirePriceResult getStockPrice(final Long memberId, final Long memberAccountId,
		final String productNumber) {
		// 유저만의 계정 정보 조회
		MemberAccount memberAccount = memberAccountRepository.findByIdAndMemberId(memberId, memberAccountId)
			.orElseThrow(() -> ApiException.from(ErrorCode.MEMBER_ACCOUNT_NOT_FOUND));

		// 가격 조회
		KisClient client = createKisClient(memberAccount);
		InquirePriceApi priceApi = new InquirePriceApi(productNumber);
		InquirePriceResult result = client.execute(priceApi);

		// 응답 예외처리
		if (!result.getRtCd().equals("0")) {
			log.error("[DomesticStockService] getStockPrice - 가격 조회 실패: {}", result.getMsg1());
			throw ApiException.from(KIS_CLIENT_ERROR);
		}

		return result;
	}

	// 국내주식 매수
	public OrderCashResult buyStock(final Long memberId, final Long memberAccountId, DomesticTradeRequest request) {
		// 유저만의 계정 정보 조회
		MemberAccount memberAccount = memberAccountRepository.findByIdAndMemberId(memberId, memberAccountId)
			.orElseThrow(() -> ApiException.from(ErrorCode.MEMBER_ACCOUNT_NOT_FOUND));

		// 매수 주문
		KisClient client = createKisClient(memberAccount);
		OrderCashApi orderApi = new OrderCashApi(
			request.getProductNumber(),
			request.getQuantity().toString(),
			request.getPrice().toString()
		);

		// 모의투자시 trId 변경
		if (memberAccount.getAccountType() == AccountType.PAPER_TRADE) {
			orderApi.setTrId("VTTC0012U");
		}

		OrderCashResult result = client.execute(orderApi);

		// 응답 예외처리
		if (!result.getRtCd().equals("0")) {
			log.error("[DomesticStockService] buyStock - 매수 주문 실패: {}", result.getMsg1());
			throw ApiException.from(KIS_CLIENT_ERROR);
		}

		return result;
	}

	// 국내주식 매도
	public OrderCashResult sellStock(final Long memberId, final Long memberAccountId, DomesticTradeRequest request) {
		// 유저만의 계정 정보 조회
		MemberAccount memberAccount = memberAccountRepository.findByIdAndMemberId(memberId, memberAccountId)
			.orElseThrow(() -> ApiException.from(ErrorCode.MEMBER_ACCOUNT_NOT_FOUND));

		// 매도 주문 (OrderCashApi 사용, 주문구분을 매도로 설정)
		KisClient client = createKisClient(memberAccount);
		OrderCashApi orderApi = new OrderCashApi(
			request.getProductNumber(),
			request.getQuantity().toString(),
			request.getPrice().toString()
		);

		// 모의투자시 trId 변경
		if (memberAccount.getAccountType() == AccountType.PAPER_TRADE) {
			orderApi.setTrId("VTTC0011U");
		}

		OrderCashResult result = client.execute(orderApi);

		// 응답 예외처리
		if (!result.getRtCd().equals("0")) {
			log.error("[DomesticStockService] sellStock - 매도 주문 실패: {}", result.getMsg1());
			throw ApiException.from(KIS_CLIENT_ERROR);
		}

		return result;
	}

	// 국내주식 잔고 조회
	public InquireBalanceResult getBalance(final Long memberId, final Long memberAccountId) {
		// 유저만의 계정 정보 조회
		MemberAccount memberAccount = memberAccountRepository.findByIdAndMemberId(memberId, memberAccountId)
			.orElseThrow(() -> ApiException.from(ErrorCode.MEMBER_ACCOUNT_NOT_FOUND));

		// 잔고 조회
		KisClient client = createKisClient(memberAccount);
		InquireBalanceApi balanceApi = new InquireBalanceApi();

		// 모의투자시 trId 변경
		if (memberAccount.getAccountType() == AccountType.PAPER_TRADE) {
			balanceApi.setTrId("VTTC8434R");
		}
		InquireBalanceResult result = client.execute(balanceApi);

		// 응답 예외처리
		if (!result.getRtCd().equals("0")) {
			log.error("[DomesticStockService] getBalance - 잔고 조회 실패: {}", result.getMsg1());
			throw ApiException.from(KIS_CLIENT_ERROR);
		}

		return result;
	}

	// 계좌 존재 확인, 오류 시 예외 발생
	public void validateAccountExists(
		final String appKey,
		final String secretKey,
		final String accountNumber,
		final String accountProductCode,
		final AccountType accountType
	) {
		// 잔고 조회로 계좌 존재 확인
		KisClient client = createKisClient(appKey, secretKey, accountNumber, accountProductCode, accountType);
		InquireBalanceApi balanceApi = new InquireBalanceApi();

		// 모의투자시 trId 변경
		if (accountType == AccountType.PAPER_TRADE) {
			balanceApi.setTrId("VTTC8434R");
		}

		InquireBalanceResult result = client.execute(balanceApi);

		// 응답 예외처리
		if (!result.getRtCd().equals("0")) {
			log.error("[DomesticStockService] validateAccountExists - 계좌 인증 실패: {}", result.getMsg1());
			throw ApiException.from(KIS_ACCOUNT_NOT_FOUND);
		}
	}

	// KIS 클라이언트 생성
	private KisClient createKisClient(final MemberAccount memberAccount) {
		Credentials credentials = new Credentials(
			memberAccount.getDecryptedAppKey(),
			memberAccount.getDecryptedSecretKey(),
			memberAccount.getAccountNumber(),
			memberAccount.getAccountProductCode()
		);

		Configuration config = new Configuration(memberAccount.getAccountType());
		config.addCredentials(credentials);

		return new KisClient(config);
	}

	// KIS 클라이언트 생성
	private KisClient createKisClient(
		final String appKey,
		final String secretKey,
		final String accountNumber,
		final String accountProductCode,
		final AccountType accountType
	) {
		Credentials credentials = new Credentials(
			appKey,
			secretKey,
			accountNumber,
			accountProductCode
		);

		Configuration config = new Configuration(accountType);
		config.addCredentials(credentials);

		return new KisClient(config);
	}
}

