package com.backend.domains.stock.controller;

import java.util.ArrayList;
import java.util.List;

import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import com.backend.common.dto.DataResponse;
import com.backend.common.dto.ErrorResponse;
import com.backend.common.util.memberLoader.MemberLoader;
import com.backend.domains.member.domain.Member;
import com.backend.domains.stock.dto.StockDTO.FindDomesticStockPopularChartResponse;
import com.backend.domains.stock.dto.StockDTO.FindDomesticStockPriceChartResponse;
import com.backend.domains.stock.dto.StockDTO.FindSuggestedKeywordResponse;
import com.backend.domains.stock.dto.StockDTO.GetStockBalanceRealizedProfitAndLossResponse;
import com.backend.domains.stock.dto.StockDTO.GetStockBalanceResponse;
import com.backend.domains.stock.dto.StockDTO.GetStockPriceResponse;
import com.backend.domains.stock.dto.StockDTO.OrderStockRequest;
import com.backend.domains.stock.service.StockService;
import lombok.RequiredArgsConstructor;

@Tag(name = "STOCK API", description = "주식에 대한 API입니다.")
@RestController
@RequestMapping("/api/stocks")
@RequiredArgsConstructor
public class StockController {

	private final MemberLoader memberLoader;
	private final StockService stockService;

	// 삭제될 API
	@PostMapping("/buy")
	public ResponseEntity<DataResponse<Void>> buyStock(OrderStockRequest request) {
		Member member = memberLoader.getMember();

		stockService.orderStock(member, request);

		return ResponseEntity.ok(DataResponse.ok());
	}

	@PostMapping("/sell")
	public ResponseEntity<DataResponse<Void>> sellStock(OrderStockRequest request) {
		Member member = memberLoader.getMember();

		stockService.sellStock(member, request);

		return ResponseEntity.ok(DataResponse.ok());
	}

	@GetMapping("/recommendations")
	public ResponseEntity<DataResponse<List<FindDomesticStockPopularChartResponse>>> getStockRecommendations(
		@RequestParam("size") @Min(value = 1, message = "size는 1이상이어야 합니다.") @Max(value = 10, message = "size는 10이하이어야 합니다.") int size,
		@RequestParam("page") @Min(value = 0, message = "page는 0이상이어야 합니다.") int page) {
		Pageable pageable = PageRequest.of(page, size);

		List<FindDomesticStockPopularChartResponse> popularStocks = new ArrayList<>();

		popularStocks.add(new FindDomesticStockPopularChartResponse("삼성전자", "005930"));
		popularStocks.add(new FindDomesticStockPopularChartResponse("SK하이닉스", "000660"));
		popularStocks.add(new FindDomesticStockPopularChartResponse("이엔셀", "190380"));
		popularStocks.add(new FindDomesticStockPopularChartResponse("에코프로비엠", "247540"));
		popularStocks.add(new FindDomesticStockPopularChartResponse("에코프로", "086520"));
		popularStocks.add(new FindDomesticStockPopularChartResponse("금양", "001570"));
		popularStocks.add(new FindDomesticStockPopularChartResponse("HLB", "028300"));
		popularStocks.add(new FindDomesticStockPopularChartResponse("한미반도체", "042700"));
		popularStocks.add(new FindDomesticStockPopularChartResponse("유한양행", "000100"));
		popularStocks.add(new FindDomesticStockPopularChartResponse("카카오", "035720"));

		new PageImpl<>(popularStocks, pageable, popularStocks.size());

		return ResponseEntity.ok(DataResponse.from(popularStocks));
	}

	@GetMapping("/balance")
	@Operation(
		summary = "주식 잔고 조회",
		description = """
			주식 잔고 조회 api 입니다.
			처음 요청할 때에는 CTX_AREA_FK100, CTX_AREA_NK100을 비워서 요청해주세요.
			다음 페이지 요청 시 응답에 존재하는 CTX_AREA_FK100, CTX_AREA_NK100을 담아서 요청해주세요.
			""",
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
	public ResponseEntity<DataResponse<GetStockBalanceResponse>> getStockBalance(
		@RequestParam(name = "CTX_AREA_FK100", defaultValue = "") String fk,
		@RequestParam(name = "CTX_AREA_NK100", defaultValue = "") String nk) {
		Member member = memberLoader.getMember();

		GetStockBalanceResponse response = stockService.getStockBalance(member, fk, nk);

		return ResponseEntity.ok(DataResponse.from(response));
	}

	@GetMapping("/balance/realized-profit-and-loss")
	@Operation(
		summary = "실현 손익 조회",
		description = """
			실현 손익 조회 api 입니다.
			실전에서만 사용가능한 api 입니다.""",
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
	public ResponseEntity<DataResponse<GetStockBalanceRealizedProfitAndLossResponse>> getBalanceRealizedProfitAndLoss() {
		Member member = memberLoader.getMember();

		GetStockBalanceRealizedProfitAndLossResponse response = stockService
			.getBalanceRealizedProfitAndLoss(member);

		return ResponseEntity.ok(DataResponse.from(response));
	}

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
	public ResponseEntity<DataResponse<GetStockPriceResponse>> getStockPrice(
		@RequestParam("productNumber") String productNumber) {
		Member member = memberLoader.getMember();

		GetStockPriceResponse response = stockService.getStockPrice(member, productNumber);

		return ResponseEntity.ok(DataResponse.from(response));
	}

	@GetMapping("/suggested-keywords")
	@Operation(
		summary = "주식 추천 키워드 조회",
		description = """
			주식 추천 키워드 조회 api 입니다.""",
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
	public ResponseEntity<DataResponse<List<FindSuggestedKeywordResponse>>> findSuggestedKeywords(
		@RequestParam("keyword") String keyword) {
		List<FindSuggestedKeywordResponse> responses = stockService.findSuggestedKeywords(keyword);

		return ResponseEntity.ok(DataResponse.from(responses));
	}

	@GetMapping("/update/removed")
	public ResponseEntity<?> updateRemovedStocks() {
		stockService.updateDomesticStocks();

		return ResponseEntity.ok().build();
	}

	@GetMapping("/price-chart")
	@Operation(
		summary = "주식 가격 차트 조회",
		description = """
			주식 가격 차트 조회 api 입니다.
			periodCode는 D, W, M, Y 중 하나여야 합니다.""",
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
	public ResponseEntity<DataResponse<FindDomesticStockPriceChartResponse>> findDomesticStockPriceChart(
		@RequestParam("productNumber") String productNumber,
		@Pattern(regexp = "D|W|M|Y", message = "periodCode는 D, W, M, Y 중 하나여야 합니다.") @RequestParam("periodCode") String periodCode) {
		Member member = memberLoader.getMember();

		FindDomesticStockPriceChartResponse response = stockService.findDomesticStockPriceChart(member, productNumber,
			periodCode);

		return ResponseEntity.ok(DataResponse.from(response));
	}

	@PostMapping("/update/stocks")
	public ResponseEntity<?> updateStocks() {
		stockService.updateDomesticStocks();

		return ResponseEntity.ok().build();
	}
}
