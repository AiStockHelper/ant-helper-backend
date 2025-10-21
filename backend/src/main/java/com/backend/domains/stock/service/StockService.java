package com.backend.domains.stock.service;

import static com.backend.common.exception.ErrorCode.*;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import com.backend.common.exception.ApiException;
import com.backend.common.properties.KisProperties;
import com.backend.common.properties.PublicDataPortalProperties;
import com.backend.common.util.webClient.WebClientUtil;
import com.backend.domains.broker.KisAccessToken;
import com.backend.domains.broker.service.KisAccessTokenService;
import com.backend.domains.member.MemberService;
import com.backend.domains.member.domain.Member;
import com.backend.domains.stock.domain.DomesticStock;
import com.backend.domains.stock.dto.StockDTO.FindDomesticStockPriceChartResponse;
import com.backend.domains.stock.dto.StockDTO.FindSuggestedKeywordResponse;
import com.backend.domains.stock.dto.StockDTO.GetStockBalanceRealizedProfitAndLossResponse;
import com.backend.domains.stock.dto.StockDTO.GetStockBalanceResponse;
import com.backend.domains.stock.dto.StockDTO.GetStockPriceResponse;
import com.backend.domains.stock.dto.StockDTO.GetSuggestedKeywordsDTO;
import com.backend.domains.stock.dto.StockDTO.GetSuggestedKeywordsDTO.Response.Body.Items.Item;
import com.backend.domains.stock.dto.StockDTO.KisBaseResponse;
import com.backend.domains.stock.dto.StockDTO.KisOrderStockRequest;
import com.backend.domains.stock.dto.StockDTO.OrderStockRequest;
import com.backend.domains.stock.dto.StockDTO.OrderStockResponse;
import com.backend.domains.stock.repository.DomesticStockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class StockService {

	private final KisProperties kisProperties;
	private final KisAccessTokenService kisAccessTokenService;
	private final MemberService memberService;
	private final WebClientUtil webClientUtil;
	private final PublicDataPortalProperties publicDataPortalProperties;
	private final DomesticStockRepository domesticStockRepository;
	private final static int GET_SUGGESTED_KEYWORDS_SIZE = 200;

	// 헤더 설정
	private Map<String, String> makeHeaders(Member member, String trId) {
		KisAccessToken kisAccessToken = kisAccessTokenService.findKisAccessToken(member.getId()).
			orElseThrow(() -> ApiException.from(KIS_ACCESS_TOKEN_NOT_FOUND));
		String accessToken = kisAccessToken.getAccessToken();

		Map<String, String> headers = new HashMap<>();
		headers.put("authorization", accessToken);
		headers.put("appkey", memberService.getDecryptedAppKey(member));
		headers.put("appsecret", memberService.getDecryptedSecretKey(member));
		headers.put("tr_id", trId);

		return headers;
	}

	// 응답 확인
	private void checkResponse(KisBaseResponse response) {
		if (!response.getRt_cd().equals("0")) {
			throw ApiException.of(HttpStatus.BAD_REQUEST, response.getMsg1());
		}
	}

	// 주식 사기
	public void orderStock(Member member, OrderStockRequest request) {
		String uri = "/uapi/domestic-stock/v1/trading/order-cash";

		// 헤더 설정
		Map<String, String> headers = makeHeaders(member, kisProperties.getOrderTrId());

		// 본문 설정
		KisOrderStockRequest kisOrderStockRequest = makeKisOrderStockRequestFromOrderStockRequestAndMember(member,
			request);

		OrderStockResponse response = webClientUtil.postFromKis(
			headers,
			uri,
			kisOrderStockRequest,
			OrderStockResponse.class);

		checkResponse(response);
	}

	// 주식 팔기
	public void sellStock(Member member, OrderStockRequest request) {
		String uri = "/uapi/domestic-stock/v1/trading/order-cash";

		// 헤더 설정
		Map<String, String> headers = makeHeaders(member, kisProperties.getSellTrId());

		// 본문 설정
		KisOrderStockRequest kisOrderStockRequest = makeKisOrderStockRequestFromOrderStockRequestAndMember(member,
			request
		);

		OrderStockResponse response = webClientUtil.postFromKis(
			headers,
			uri,
			kisOrderStockRequest,
			OrderStockResponse.class);

		checkResponse(response);
	}

	// KisOrderStockRequest를 OrderStockRequest와 Member로 만들어주는 메서드
	private KisOrderStockRequest makeKisOrderStockRequestFromOrderStockRequestAndMember(Member member,
		OrderStockRequest request) {
		return KisOrderStockRequest.builder()
			.CANO(member.getComprehensiveAccountNumber())
			.ACNT_PRDT_CD(member.getAccountProductCode())
			.PDNO(request.getPDNO())
			.ORD_DVSN(request.getORD_DVSN())
			.ORD_QTY(request.getORD_QTY())
			.ORD_UNPR(request.getORD_UNPR())
			.build();
	}

	// 주식 잔고 조회
	public GetStockBalanceResponse getStockBalance(Member member, String fk, String nk) {
		String uri = "/uapi/domestic-stock/v1/trading/inquire-balance";

		// 헤더 설정
		Map<String, String> headers = makeHeaders(member, kisProperties.getGetBalanceTrId());

		// 파라미터 설정
		MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
		params.add("CANO", member.getComprehensiveAccountNumber()); // 종합계좌번호
		params.add("ACNT_PRDT_CD", member.getAccountProductCode()); // 계좌상품코드
		params.add("AFHR_FLPR_YN", "N"); // 시간외단일가 여부 (Y: 포함, N: 포함하지 않음)
		params.add("OFL_YN", ""); // 오프라인 여부 (기본값: 공란)
		params.add("INQR_DVSN", "02"); // 조회 구분 (01: 대출일별, 02: 종목별)
		params.add("UNPR_DVSN", "01"); // 단가 구분 (01: 기본값)
		params.add("FUND_STTL_ICLD_YN", "N"); // 펀드 결제분 포함 여부 (Y: 포함, N: 포함하지 않음)
		params.add("FNCG_AMT_AUTO_RDPT_YN", "N"); // 융자 금액 자동 상환 여부 (기본값: N)
		params.add("PRCS_DVSN", "00"); // 처리 구분 (00: 전일 매매 포함, 01: 전일 매매 미포함)
		params.add("CTX_AREA_FK100", fk); // 연속 조회 검색 조건 100 (최초 조회 시 공란)
		params.add("CTX_AREA_NK100", nk); // 연속 조회 키 100 (최초 조회 시 공란)

		GetStockBalanceResponse response = webClientUtil.getFromKis(
			headers,
			uri,
			params,
			GetStockBalanceResponse.class);

		checkResponse(response);

		return response;
	}

	// 주식잔고조회_실현손익
	public GetStockBalanceRealizedProfitAndLossResponse getBalanceRealizedProfitAndLoss(Member member) {
		String uri = "/uapi/domestic-stock/v1/trading/inquire-balance-rlz-pl";

		// 헤더 설정
		Map<String, String> headers = makeHeaders(member, kisProperties.getGetBalanceRealizedProfitAndLossTrId());
		headers.put("custtype", "P"); // 고객타입(P: 개인, B: 기업)

		// 파라미터 설정
		MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
		params.add("CANO", member.getComprehensiveAccountNumber()); // 종합계좌번호
		params.add("ACNT_PRDT_CD", member.getAccountProductCode()); // 계좌상품코드
		params.add("AFHR_FLPR_YN", "N"); // 시간외단일가여부 (기본값 N)
		params.add("OFL_YN", ""); // 오프라인여부
		params.add("INQR_DVSN", "00"); // 조회구분 (기본값 00)
		params.add("UNPR_DVSN", "01"); // 단가구분 (기본값 01)
		params.add("FUND_STTL_ICLD_YN", "N"); // 펀드결제포함여부 (기본값 N)
		params.add("FNCG_AMT_AUTO_RDPT_YN", "N"); // 융자금액자동상환여부 (기본값 N)
		params.add("PRCS_DVSN", "00"); // PRCS_DVSN (기본값 00)
		params.add("COST_ICLD_YN", ""); // 비용포함여부
		params.add("CTX_AREA_FK100", ""); // 연속조회검색조건100 (최초 조회시 공란)
		params.add("CTX_AREA_NK100", ""); // 연속조회키100 (최초 조회시 공란)

		GetStockBalanceRealizedProfitAndLossResponse response = webClientUtil.getFromKis(
			headers,
			uri,
			params,
			GetStockBalanceRealizedProfitAndLossResponse.class);

		checkResponse(response);

		return response;
	}

	// 주식 현재가 조회
	public GetStockPriceResponse getStockPrice(Member member, String productNumber) {
		String uri = "/uapi/domestic-stock/v1/quotations/inquire-price";

		// 헤더 설정
		Map<String, String> headers = makeHeaders(member, kisProperties.getGetPriceTrId());

		// 파라미터 설정
		MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
		params.add("FID_COND_MRKT_DIV_CODE", "J"); // 'J': 주식, ETF, ETN / 'W': ELW
		params.add("FID_INPUT_ISCD", productNumber); // FID 입력 종목코드: 6자리 종목번호 또는 ETN의 경우 'Q'로 시작하는 코드

		GetStockPriceResponse response = webClientUtil.getFromKis(
			headers,
			uri,
			params,
			GetStockPriceResponse.class);

		checkResponse(response);

		// 이름 설정
		String name = findDomesticStockByProductNumber(productNumber).getName();
		response.getOutput().setName(name);

		return response;
	}

	// 국내 주식 목록 가져오기
	public GetSuggestedKeywordsDTO getDomesticStocks(int size, int page, String keyword, String day) {
		String uri = "/1160100/service/GetKrxListedInfoService/getItemInfo";

		// 파라미터 설정
		MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
		params.add("serviceKey", publicDataPortalProperties.getKeywordsKey());
		params.add("resultType", "json");
		params.add("numOfRows", String.valueOf(size));
		params.add("pageNo", String.valueOf(page));
		params.add("likeItmsNm", keyword); // 검색어
		params.add("basDt", day); // 날짜

		return webClientUtil.getFromPublicDataPortal(
			new HashMap<>(),
			uri,
			params,
			GetSuggestedKeywordsDTO.class);

	}

	// 국내 주식 목록 업데이트
	@Transactional(rollbackFor = {ApiException.class})
	public void updateDomesticStocks() {
		String yesterday = getYesterday();

		// 주식 전체 개수 가져오기
		int totalCount = getDomesticStocks(1, 1, "", yesterday)
			.getResponse()
			.getBody()
			.getTotalCount();

		if (totalCount == 0) { // 전 날이 휴일이면 저장 x
			return;
		}

		// 주식 전체 삭제
		domesticStockRepository.deleteAll();

		// 전체 페이지 개수 계산하기
		int totalPage = calculateTotalPage(totalCount);

		for (int i = 1; i <= totalPage; i++) {
			List<DomesticStock> domesticStocks = getDomesticStocks(GET_SUGGESTED_KEYWORDS_SIZE, i, "", yesterday)
				.getResponse()
				.getBody()
				.getItems()
				.getItem()
				.stream()
				.map(Item::toEntity)
				.toList();

			saveDomesticStocks(domesticStocks);
		}
	}

	// 주식 목록 저장
	private void saveDomesticStocks(List<DomesticStock> domesticStocks) {
		domesticStockRepository.saveAll(domesticStocks);
	}

	// 전 날을 String으로 가져오기
	private String getYesterday() {
		LocalDate yesterday = LocalDate.now().minusDays(1);
		return yesterday.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
	}

	// 전체 페이지 개수 계산하기
	private int calculateTotalPage(int totalCount) {
		return (totalCount + GET_SUGGESTED_KEYWORDS_SIZE - 1) / GET_SUGGESTED_KEYWORDS_SIZE;
	}

	// 주식 추천 검색어 검색하기
	public List<FindSuggestedKeywordResponse> findSuggestedKeywords(String keyword) {
		List<DomesticStock> domesticStocks = domesticStockRepository.findAllByKeyword(
			keyword,
			PageRequest.of(0, 10));

		return domesticStocks.stream()
			.map(FindSuggestedKeywordResponse::from)
			.toList();
	}

	// 국내 주식 조회
	private DomesticStock findDomesticStockByProductNumber(String productNumber) {
		return domesticStockRepository.findByProductNumber(productNumber)
			.orElseThrow(() -> ApiException.from(STOCK_NOT_FOUND));
	}

	// 국내 주식 기간별 시세 조회
	public FindDomesticStockPriceChartResponse findDomesticStockPriceChart(Member member, String productNumber,
		String periodCode) {
		String uri = "/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice";
		LocalDate today = LocalDate.now();
		String startDate = makeDateToString(calculateStartDate(today, periodCode));
		String endDate = makeDateToString(today);

		// 헤더 설정
		Map<String, String> headers = makeHeaders(member, kisProperties.getFindDomesticStockPriceChartId());

		// 파라미터 설정
		MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
		params.add("FID_COND_MRKT_DIV_CODE", "J"); // 'J': 주식, ETF, ETN / 'W': ELW
		params.add("FID_INPUT_ISCD", productNumber); // FID 입력 종목코드: 6자리 종목번호 또는 ETN의 경우 'Q'로 시작하는 코드
		params.add("FID_INPUT_DATE_1", startDate);
		params.add("FID_INPUT_DATE_2", endDate);
		params.add("FID_PERIOD_DIV_CODE", periodCode);
		params.add("FID_ORG_ADJ_PRC", "1");

		FindDomesticStockPriceChartResponse response = webClientUtil.getFromKis(
			headers,
			uri,
			params,
			FindDomesticStockPriceChartResponse.class);

		checkResponse(response);

		return response;
	}

	private LocalDate calculateStartDate(LocalDate day, String periodCode) {
		LocalDate startDate;

		if (periodCode.equals("D")) { // 일봉: 저번 달
			startDate = day.minusMonths(1);
		} else if (periodCode.equals("W")) { // 주봉: 이번 주의 첫 영업일 (월요일)
			startDate = day.minusMonths(6).with(DayOfWeek.MONDAY);
		} else if (periodCode.equals("M")) { // 월봉: 작년 이날
			startDate = day.minusYears(3).withDayOfMonth(1);
		} else if (periodCode.equals("Y")) { // 저번 해
			startDate = day.minusYears(20).withDayOfYear(1);
		} else {
			throw ApiException.from(BAD_REQUEST);
		}

		return startDate;
	}

	private String makeDateToString(LocalDate date) {
		DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");
		return date.format(formatter);
	}
}

