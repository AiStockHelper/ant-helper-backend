package com.backend.order.domestic.controller;

import static com.backend.common.exception.ErrorCode.*;

import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.backend.common.dto.DataResponse;
import com.backend.common.exception.ErrorCode;
import com.backend.common.swagger.ApiErrorMapping;
import com.backend.common.util.memberLoader.MemberLoader;
import com.backend.domains.member.domain.Member;
import com.backend.order.domestic.dto.request.DomesticTradeRequest;
import com.backend.order.domestic.service.DomesticStockService;
import com.backend.order.kis.kis_api.api.rest.quotations.InquirePriceResult;
import com.backend.order.kis.kis_api.api.rest.trading.InquireBalanceResult;
import com.backend.order.kis.kis_api.api.rest.trading.OrderCashResult;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Tag(name = "STOCK API", description = "주식에 대한 API입니다.")
@RestController
@RequestMapping("/api/stocks")
@RequiredArgsConstructor
public class DomesticStockController {

	private final MemberLoader memberLoader;
	private final DomesticStockService domesticStockService;

	@PostMapping("/buy")
	@Operation(
		summary = "국내 주식 매수",
		description = "국내 주식 매수 API입니다."
	)
	@ApiErrorMapping({
		MEMBER_ACCOUNT_NOT_FOUND,
		ErrorCode.KIS_CLIENT_ERROR
	})
	public ResponseEntity<DataResponse<OrderCashResult>> buyStock(
		@RequestParam("memberAccountId") Long memberAccountId,
		@RequestBody @Valid DomesticTradeRequest request
	) {
		Member member = memberLoader.getMember();
		OrderCashResult orderCashResult = domesticStockService.buyStock(member.getId(), memberAccountId, request);

		return ResponseEntity.ok(DataResponse.from(orderCashResult));
	}

	@PostMapping("/sell")
	@Operation(
		summary = "국내 주식 매도",
		description = "국내 주식 매도 API입니다."
	)
	@ApiErrorMapping({
		MEMBER_ACCOUNT_NOT_FOUND,
		ErrorCode.KIS_CLIENT_ERROR
	})
	public ResponseEntity<DataResponse<OrderCashResult>> sellStock(
		@RequestParam("memberAccountId") Long memberAccountId,
		@RequestParam @Valid DomesticTradeRequest request
	) {
		Member member = memberLoader.getMember();

		OrderCashResult orderCashResult = domesticStockService.sellStock(member.getId(), memberAccountId, request);

		return ResponseEntity.ok(DataResponse.from(orderCashResult));
	}

	@GetMapping("/balance")
	@Operation(
		summary = "주식 잔고 조회",
		description = """
			주식 잔고 조회 api 입니다.
			"""
	)
	@ApiErrorMapping({MEMBER_ACCOUNT_NOT_FOUND, KIS_CLIENT_ERROR})
	public ResponseEntity<DataResponse<InquireBalanceResult>> getStockBalance(
		@RequestParam("memberAccountId") Long memberAccountId
	) {
		Member member = memberLoader.getMember();

		InquireBalanceResult result = domesticStockService.getBalance(member.getId(), memberAccountId);

		return ResponseEntity.ok(DataResponse.from(result));
	}

	// @GetMapping("/balance/realized-profit-and-loss")
	// @Operation(
	// 	summary = "실현 손익 조회",
	// 	description = """
	// 		실현 손익 조회 api 입니다.
	// 		실전에서만 사용가능한 api 입니다.""",
	// 	responses = {
	// 		@ApiResponse(
	// 			responseCode = "200",
	// 			description = "성공"
	// 		),
	// 		@ApiResponse(

	// 			responseCode = "400",
	// 			description = "요청 오류입니다.",
	// 			content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	// 		),
	// 		@ApiResponse(
	// 			responseCode = "401",
	// 			description = "유효하지 않은 액세스 토큰입니다.",
	// 			content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	// 		),
	// 		@ApiResponse(
	// 			responseCode = "500",
	// 			description = "요청 오류입니다.",
	// 			content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	// 		)
	// 	}
	// )
	// public ResponseEntity<DataResponse<GetStockBalanceRealizedProfitAndLossResponse>> getBalanceRealizedProfitAndLoss() {
	// 	Member member = memberLoader.getMember();
	//
	// 	GetStockBalanceRealizedProfitAndLossResponse response = domesticStockService
	// 		.getBalanceRealizedProfitAndLoss(member);
	//
	// 	return ResponseEntity.ok(DataResponse.from(response));
	// }
	//
	@GetMapping("/price")
	@Operation(
		summary = "주식 가격 조회",
		description = """
			주식 가격 조회 api 입니다.""",
		responses = {
			@ApiResponse(
				responseCode = "200",
				description = "성공"
			),
			@ApiResponse(
				responseCode = "400",
				description = "요청 오류입니다.",
				content = @Content(schema = @Schema(implementation = ErrorResponse.class))
			),
			@ApiResponse(
				responseCode = "401",
				description = "유효하지 않은 액세스 토큰입니다.",
				content = @Content(schema = @Schema(implementation = ErrorResponse.class))
			),
			@ApiResponse(
				responseCode = "500",
				description = "요청 오류입니다.",
				content = @Content(schema = @Schema(implementation = ErrorResponse.class))
			)
		}
	)
	public ResponseEntity<DataResponse<InquirePriceResult>> getStockPrice(
		@RequestParam("memberAccountId") Long memberAccountId,
		@RequestParam("productNumber") String productNumber
	) {
		Member member = memberLoader.getMember();

		InquirePriceResult result = domesticStockService.getStockPrice(member.getId(), memberAccountId,
			productNumber);

		return ResponseEntity.ok(DataResponse.from(result));
	}
	//
	// @GetMapping("/suggested-keywords")
	// @Operation(
	// 	summary = "주식 추천 키워드 조회",
	// 	description = """
	// 		주식 추천 키워드 조회 api 입니다.""",
	// 	responses = {
	// 		@ApiResponse(
	// 			responseCode = "200",
	// 			description = "성공"
	// 		),
	// 		@ApiResponse(
	// 			responseCode = "400",
	// 			description = "요청 오류입니다.",
	// 			content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	// 		),
	// 		@ApiResponse(
	// 			responseCode = "401",
	// 			description = "유효하지 않은 액세스 토큰입니다.",
	// 			content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	// 		),
	// 		@ApiResponse(
	// 			responseCode = "500",
	// 			description = "요청 오류입니다.",
	// 			content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	// 		)
	// 	}
	// )
	// public ResponseEntity<DataResponse<List<FindSuggestedKeywordResponse>>> findSuggestedKeywords(
	// 	@RequestParam("keyword") String keyword) {
	// 	List<FindSuggestedKeywordResponse> responses = domesticStockService.findSuggestedKeywords(keyword);
	//
	// 	return ResponseEntity.ok(DataResponse.from(responses));
	// }
	//
	// @GetMapping("/update/removed")
	// public ResponseEntity<?> updateRemovedStocks() {
	// 	domesticStockService.updateDomesticStocks();
	//
	// 	return ResponseEntity.ok().build();
	// }
	//
	// @GetMapping("/price-chart")
	// @Operation(
	// 	summary = "주식 가격 차트 조회",
	// 	description = """
	// 		주식 가격 차트 조회 api 입니다.
	// 		periodCode는 D, W, M, Y 중 하나여야 합니다.""",
	// 	responses = {
	// 		@ApiResponse(
	// 			responseCode = "200",
	// 			description = "성공"
	// 		),
	// 		@ApiResponse(
	// 			responseCode = "400",
	// 			description = "요청 오류입니다.",
	// 			content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	// 		),
	// 		@ApiResponse(
	// 			responseCode = "401",
	// 			description = "유효하지 않은 액세스 토큰입니다.",
	// 			content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	// 		),
	// 		@ApiResponse(
	// 			responseCode = "500",
	// 			description = "요청 오류입니다.",
	// 			content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	// 		)
	// 	}
	// )
	// public ResponseEntity<DataResponse<FindDomesticStockPriceChartResponse>> findDomesticStockPriceChart(
	// 	@RequestParam("productNumber") String productNumber,
	// 	@Pattern(regexp = "D|W|M|Y", message = "periodCode는 D, W, M, Y 중 하나여야 합니다.") @RequestParam("periodCode") String periodCode) {
	// 	Member member = memberLoader.getMember();
	//
	// 	FindDomesticStockPriceChartResponse response = domesticStockService.findDomesticStockPriceChart(member, productNumber,
	// 		periodCode);
	//
	// 	return ResponseEntity.ok(DataResponse.from(response));
	// }
}
