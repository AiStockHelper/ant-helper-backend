package com.backend.order.domestic.service;

import static com.backend.common.exception.ErrorCode.*;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backend.common.exception.ApiException;
import com.backend.common.exception.ErrorCode;
import com.backend.domains.member.domain.MemberAccount;
import com.backend.domains.member.repository.MemberAccountRepository;
import com.backend.order.domestic.dto.request.DomesticTradeRequest;
import com.backend.order.domestic.dto.request.InquireDailyOrderExecutionRequest;
import com.backend.order.domestic.dto.request.ModifyOrderRequest;
import com.backend.order.kis.enums.AccountType;
import com.backend.order.kis.kis_api.api.rest.quotations.InquirePriceApi;
import com.backend.order.kis.kis_api.api.rest.quotations.InquirePriceResult;
import com.backend.order.kis.kis_api.api.rest.trading.InquireBalanceApi;
import com.backend.order.kis.kis_api.api.rest.trading.InquireBalanceResult;
import com.backend.order.kis.kis_api.api.rest.trading.InquireDailyCcldApi;
import com.backend.order.kis.kis_api.api.rest.trading.InquireDailyCcldResult;
import com.backend.order.kis.kis_api.api.rest.trading.OrderCashApi;
import com.backend.order.kis.kis_api.api.rest.trading.OrderCashResult;
import com.backend.order.kis.kis_api.api.rest.trading.OrderResvRvsecnclApi;
import com.backend.order.kis.kis_api.api.rest.trading.OrderResvRvsecnclResult;
import com.backend.order.kis.kis_client.KisClient;
import com.backend.order.kis.kis_client.config.Configuration;
import com.backend.order.kis.kis_client.config.Credentials;
import com.backend.order.kis.kis_client.exception.KisClientException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// 주의 : 절대 유저 민감정보를 파라미터나 리턴값으로 주지 말 것(AI 툴에서 노출될 수 있음)
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DomesticStockService {

	private final MemberAccountRepository memberAccountRepository;

	// 국내주식 가격 조회
	@Tool(name = "get_korean_stock_price", description = "특정 국내주식의 현재 가격을 조회합니다.")
	public InquirePriceResult getStockPrice(
		final Long memberId,
		final Long memberAccountId,
		final String productNumber
	) {
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
			throw new KisClientException(result.getMsg1());
		}

		return result;
	}

	// 국내주식 매수
	@Tool(name = "buy_korean_stock", description = "국내주식을 매수합니다.")
	public OrderCashResult buyStock(final Long memberId, final Long memberAccountId, DomesticTradeRequest request) {
		// 유저만의 계정 정보 조회
		MemberAccount memberAccount = memberAccountRepository.findByIdAndMemberId(memberId, memberAccountId)
			.orElseThrow(() -> ApiException.from(ErrorCode.MEMBER_ACCOUNT_NOT_FOUND));

		// 매수 주문
		KisClient client = createKisClient(memberAccount);
		OrderCashApi api = new OrderCashApi(
			request.getProductNumber(),
			request.getQuantity().toString(),
			request.getPrice().toString(),
			request.getExchangeType().toString()
		);

		// 모의투자시 trId 변경
		if (memberAccount.getAccountType() == AccountType.PAPER_TRADE) {
			api.setTrId("VTTC0012U");
		}

		OrderCashResult result = client.execute(api);

		// 응답 예외처리
		if (!result.getRtCd().equals("0")) {
			log.error("[DomesticStockService] buyStock - 매수 주문 실패: {}", result.getMsg1());
			throw new KisClientException(result.getMsg1());
		}

		return result;
	}

	// 국내주식 매도
	@Tool(name = "sell_korean_stock", description = "국내주식을 매도합니다.")
	public OrderCashResult sellStock(final Long memberId, final Long memberAccountId, DomesticTradeRequest request) {
		// 유저만의 계정 정보 조회
		MemberAccount memberAccount = memberAccountRepository.findByIdAndMemberId(memberId, memberAccountId)
			.orElseThrow(() -> ApiException.from(ErrorCode.MEMBER_ACCOUNT_NOT_FOUND));

		// 매도 주문 (OrderCashApi 사용, 주문구분을 매도로 설정)
		KisClient client = createKisClient(memberAccount);
		OrderCashApi api = new OrderCashApi(
			request.getProductNumber(),
			request.getQuantity().toString(),
			request.getPrice().toString(),
			request.getExchangeType().toString()
		);

		// 모의투자시 trId 변경
		if (memberAccount.getAccountType() == AccountType.PAPER_TRADE) {
			api.setTrId("VTTC0011U");
		}

		OrderCashResult result = client.execute(api);

		// 응답 예외처리
		if (!result.getRtCd().equals("0")) {
			log.error("[DomesticStockService] sellStock - 매도 주문 실패: {}", result.getMsg1());
			throw new KisClientException(result.getMsg1());
		}

		return result;
	}

	// 국내주식 정정
	@Tool(name = "modify_korean_stock_order", description = "국내주식 주문을 정정합니다.")
	public OrderResvRvsecnclResult modifyOrder(
		final Long memberId,
		final Long memberAccountId,
		ModifyOrderRequest request
	) {
		// 유저만의 계정 정보 조회
		MemberAccount memberAccount = memberAccountRepository.findByIdAndMemberId(memberId, memberAccountId)
			.orElseThrow(() -> ApiException.from(ErrorCode.MEMBER_ACCOUNT_NOT_FOUND));

		KisClient client = createKisClient(memberAccount);
		OrderResvRvsecnclApi api = new OrderResvRvsecnclApi();
		api.setTrId("CTSC0013U"); // 정정
		api.setPdno(request.productNumber());
		api.setOrdQty(request.quantity().toString());
		api.setOrdUnpr(request.orderUnitPrice().toString());
		api.setSllBuyDvsnCd(request.sellOrBuy());
		api.setOrdDvsnCd(request.orderDivisionCode());
		api.setRsvnOrdSeq(request.reservationOrderSequence());

		// 모의투자시 사용불가
		if (memberAccount.getAccountType() == AccountType.PAPER_TRADE) {
			throw ApiException.from(KIS_PAPER_ACCOUNT_CANT_USE);
		}

		// 정정일경우
		OrderResvRvsecnclResult result = client.execute(api);

		// 응답 예외처리
		if (!result.getRtCd().equals("0")) {
			log.error("[DomesticStockService] cancelOrModifyOrder - 정정 주문 실패: {}", result.getMsg());
			throw new KisClientException(result.getMsg());
		}

		return result;
	}

	// 국내주식 취소
	@Tool(name = "cancel_korean_stock_order", description = "국내주식 주문을 취소합니다.")
	public OrderResvRvsecnclResult cancelOrder(
		final Long memberId,
		final Long memberAccountId,
		final String reservationOrderSequence // 취소할 예약주문번호
	) {
		// 유저만의 계정 정보 조회
		MemberAccount memberAccount = memberAccountRepository.findByIdAndMemberId(memberId, memberAccountId)
			.orElseThrow(() -> ApiException.from(ErrorCode.MEMBER_ACCOUNT_NOT_FOUND));

		// 취소 주문
		KisClient client = createKisClient(memberAccount);
		OrderResvRvsecnclApi api = new OrderResvRvsecnclApi();
		api.setRsvnOrdSeq(reservationOrderSequence);

		// 모의투자시 사용불가
		if (memberAccount.getAccountType() == AccountType.PAPER_TRADE) {
			throw ApiException.from(KIS_PAPER_ACCOUNT_CANT_USE);
		}

		OrderResvRvsecnclResult result = client.execute(api);

		// 응답 예외처리
		if (!result.getRtCd().equals("0")) {
			log.error("[DomesticStockService] cancelOrder - 취소 주문 실패: {}", result.getMsg());
			throw new KisClientException(result.getMsg());
		}

		return result;
	}

	// 국내주식 잔고 조회
	@Tool(name = "get_korean_stock_balance", description = "국내주식 잔고를 조회합니다.")
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
			throw new KisClientException(result.getMsg1());
		}

		return result;
	}

	// 주식일별주문체결조회
	@Tool(name = "inquire_daily_order_execution", description = "주식 일별 주문 체결 내역을 조회합니다.")
	public InquireDailyCcldResult inquireDailyOrderExecution(
		final Long memberId,
		final Long memberAccountId,
		final InquireDailyOrderExecutionRequest request
	) {
		// 유저만의 계정 정보 조회
		MemberAccount memberAccount = memberAccountRepository.findByIdAndMemberId(memberId, memberAccountId)
			.orElseThrow(() -> ApiException.from(ErrorCode.MEMBER_ACCOUNT_NOT_FOUND));

		// 주식일별주문체결조회
		KisClient client = createKisClient(memberAccount);
		InquireDailyCcldApi inquireDailyCcldApi = new InquireDailyCcldApi(
			request.inquireStartDate().format(DateTimeFormatter.ofPattern("yyyyMMdd")),
			request.inquireEndDate().format(DateTimeFormatter.ofPattern("yyyyMMdd")),
			request.exchangeType().toString()
		);

		// 조회가 3개월 이내인지
		boolean isWithinThreeMonths = request.inquireStartDate().isAfter(LocalDate.now().minusMonths(3));

		// 투자타입 및 기간에 따른 trId 설정
		if (isWithinThreeMonths && memberAccount.getAccountType() == AccountType.REAL_TRADE) {
			// 실전투자이고 3개월 이내
			inquireDailyCcldApi.setTrId("TTTC0081R");
		} else if (isWithinThreeMonths && memberAccount.getAccountType() == AccountType.PAPER_TRADE) {
			// 모의투자이고 3개월 이내
			inquireDailyCcldApi.setTrId("VTTC0081R");
		} else if (!isWithinThreeMonths && memberAccount.getAccountType() == AccountType.REAL_TRADE) {
			// 실전투자이고 3개월 이전
			inquireDailyCcldApi.setTrId("CTSC9215R");
		} else {
			// 모의투자이고 3개월 이전
			inquireDailyCcldApi.setTrId("VTSC9215R");
		}

		InquireDailyCcldResult result = client.execute(inquireDailyCcldApi);

		// 모의 투자시 trId 변경
		if (memberAccount.getAccountType() == AccountType.PAPER_TRADE) {
			inquireDailyCcldApi.setTrId("VTTC0084R");
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
			throw new KisClientException(result.getMsg1());
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

