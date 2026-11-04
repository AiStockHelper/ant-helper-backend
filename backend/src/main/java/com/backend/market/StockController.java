package com.backend.market;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.backend.common.dto.DataResponse;
import com.backend.domains.account.AccountService;
import com.backend.domains.member.domain.MemberAccount;
import com.backend.market.MarketDataService.Period;
import com.backend.market.dto.MarketDtos.Candle;
import com.backend.market.dto.MarketDtos.Prediction;
import com.backend.market.dto.MarketDtos.Quote;
import com.backend.market.dto.MarketDtos.Recommendation;
import com.backend.market.dto.MarketDtos.StockSummary;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@Tag(name = "STOCK API", description = "종목 검색 / 시세 / 예측 / 추천 (국내 + 미국)")
@RestController
@RequestMapping("/api/stocks")
@RequiredArgsConstructor
public class StockController {

	private final MarketDataService marketData;
	private final PredictionService predictionService;
	private final AccountService accountService;

	@GetMapping("/search")
	public ResponseEntity<DataResponse<List<StockSummary>>> search(
		@RequestParam String q,
		@RequestParam(required = false) String market,
		@RequestParam(defaultValue = "20") int limit
	) {
		Market m = market == null || market.isBlank() ? null : Market.parse(market);
		return ResponseEntity.ok(DataResponse.from(marketData.search(q, m, Math.max(1, Math.min(limit, 50)))));
	}

	@GetMapping("/{code}/quote")
	public ResponseEntity<DataResponse<Quote>> quote(
		@AuthenticationPrincipal Long memberId, @PathVariable String code,
		@RequestParam(required = false) String market, @RequestParam(required = false) Long accountId
	) {
		MemberAccount account = accountService.resolve(memberId, accountId);
		StockSummary s = marketData.resolve(code, parse(market));
		return ResponseEntity.ok(DataResponse.from(marketData.quote(account, s.market(), s.productNumber())));
	}

	@GetMapping("/{code}/candles")
	public ResponseEntity<DataResponse<List<Candle>>> candles(
		@AuthenticationPrincipal Long memberId, @PathVariable String code,
		@RequestParam(required = false) String market, @RequestParam(required = false) Long accountId,
		@RequestParam(defaultValue = "D") String period, @RequestParam(defaultValue = "90") int count
	) {
		MemberAccount account = accountService.resolve(memberId, accountId);
		StockSummary s = marketData.resolve(code, parse(market));
		return ResponseEntity.ok(DataResponse.from(
			marketData.candles(account, s.market(), s.productNumber(), Period.parse(period), count)));
	}

	@GetMapping("/{code}/prediction")
	public ResponseEntity<DataResponse<Prediction>> prediction(
		@AuthenticationPrincipal Long memberId, @PathVariable String code,
		@RequestParam(required = false) String market, @RequestParam(required = false) Long accountId
	) {
		MemberAccount account = accountService.resolve(memberId, accountId);
		StockSummary s = marketData.resolve(code, parse(market));
		return ResponseEntity.ok(DataResponse.from(predictionService.predict(account, s.market(), s.productNumber())));
	}

	@GetMapping("/recommendations")
	public ResponseEntity<DataResponse<List<Recommendation>>> recommendations(
		@AuthenticationPrincipal Long memberId,
		@RequestParam(required = false) Long accountId,
		@RequestParam(defaultValue = "KRX") String market,
		@RequestParam(defaultValue = "5") int size
	) {
		MemberAccount account = accountService.resolve(memberId, accountId);
		return ResponseEntity.ok(DataResponse.from(predictionService.recommend(account, market, size)));
	}

	private Market parse(String market) {
		return market == null || market.isBlank() ? null : Market.parse(market);
	}
}
