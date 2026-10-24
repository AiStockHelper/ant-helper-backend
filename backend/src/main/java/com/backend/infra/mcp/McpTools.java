package com.backend.infra.mcp;

import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.backend.common.exception.ApiException;
import com.backend.common.exception.ErrorCode;
import com.backend.common.util.TimeUtil;
import com.backend.domains.account.AccountService;
import com.backend.domains.member.domain.MemberAccount;
import com.backend.market.Market;
import com.backend.market.MarketDataService;
import com.backend.market.MarketDataService.Period;
import com.backend.market.PredictionService;
import com.backend.market.dto.MarketDtos.Candle;
import com.backend.market.dto.MarketDtos.StockSummary;

import lombok.RequiredArgsConstructor;

/**
 * 외부 MCP 클라이언트(Claude Desktop 등)에 노출하는 도구. 계정과 무관한 시세/예측/추천만 제공한다.
 * 계좌 조회와 주문은 의도적으로 노출하지 않는다.
 * 시세 조회에 쓰는 KIS 키는 MCP 요청을 인증한 회원 본인의 기본 계좌에서 가져온다.
 */
@Component
@RequiredArgsConstructor
public class McpTools {

	private final MarketDataService marketData;
	private final PredictionService predictionService;
	private final AccountService accountService;
	private final com.backend.common.properties.AppProperties props;

	@Tool(name = "search_stock", description = "종목명/티커로 국내(KRX)와 미국(NASDAQ/NYSE/AMEX) 종목을 검색합니다.")
	public Object searchStock(@ToolParam(description = "검색어. 예: 삼성전자, AAPL") String query) {
		return marketData.search(query, null, 8);
	}

	@Tool(name = "get_quote", description = "종목의 현재가와 등락률을 조회합니다.")
	public Object getQuote(
		@ToolParam(description = "종목 코드/티커 또는 이름") String symbol,
		@ToolParam(description = "시장(KRX, NASDAQ, NYSE, AMEX). 모르면 생략", required = false) String market
	) {
		StockSummary s = marketData.resolve(symbol, market == null || market.isBlank() ? null : Market.parse(market));
		return marketData.quote(account(), s.market(), s.productNumber());
	}

	@Tool(name = "get_candles", description = "종목의 최근 일봉(최대 60개)을 조회합니다.")
	public Object getCandles(
		@ToolParam(description = "종목 코드/티커 또는 이름") String symbol,
		@ToolParam(description = "시장. 모르면 생략", required = false) String market
	) {
		StockSummary s = marketData.resolve(symbol, market == null || market.isBlank() ? null : Market.parse(market));
		List<Candle> candles = marketData.candles(account(), s.market(), s.productNumber(), Period.D, 60);
		return Map.of("symbol", s.productNumber(), "name", s.name(), "currency", s.currency(), "candles", candles);
	}

	@Tool(name = "predict_stock", description = "AI 모델로 종목의 다음 거래일 방향과 예상 가격을 예측합니다. 투자 권유가 아닌 참고 지표입니다.")
	public Object predictStock(
		@ToolParam(description = "종목 코드/티커 또는 이름") String symbol,
		@ToolParam(description = "시장. 모르면 생략", required = false) String market
	) {
		StockSummary s = marketData.resolve(symbol, market == null || market.isBlank() ? null : Market.parse(market));
		return predictionService.predict(account(), s.market(), s.productNumber());
	}

	@Tool(name = "recommend_stocks", description = "대표 종목 후보군에서 내일 상승 가능성이 높은 종목을 순위로 추천합니다.")
	public Object recommendStocks(
		@ToolParam(description = "KRX(기본) 또는 US", required = false) String market,
		@ToolParam(description = "추천 개수(1~10). 기본 5", required = false) Integer count
	) {
		String group = market != null && market.equalsIgnoreCase("US") ? "US" : "KRX";
		return predictionService.recommend(account(), group, count == null ? 5 : Math.max(1, Math.min(count, 10)));
	}

	@Tool(name = "get_market_status", description = "현재 시각과 한국/미국 정규장 개장 여부를 확인합니다.")
	public Object getMarketStatus() {
		ZonedDateTime now = ZonedDateTime.now();
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("nowKst", now.withZoneSameInstant(TimeUtil.SEOUL).toString());
		out.put("krxOpen", Market.KRX.isOpenAt(now));
		out.put("usOpen", Market.NASDAQ.isOpenAt(now));
		return out;
	}

	/**
	 * 호출자 본인의 기본 계좌를 쓴다. MCP 도구가 요청 스레드 밖에서 실행되어 호출자를 알 수 없으면
	 * 운영자가 설정한 시스템 계좌(app.mcp.account-id)로 대신한다. 어느 쪽이든 시세·예측 같은 공개 데이터만 노출된다.
	 */
	private MemberAccount account() {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth != null && auth.getPrincipal() instanceof Long memberId) {
			return accountService.resolve(memberId, null);
		}
		long systemAccountId = props.getMcp().getAccountId();
		if (systemAccountId > 0) {
			return accountService.getSystemAccount(systemAccountId);
		}
		throw ApiException.of(ErrorCode.UNAUTHORIZED,
			"MCP 호출자를 확인할 수 없습니다. 서버 설정 app.mcp.account-id 에 시세 조회용 계좌 ID 를 지정해 주세요.");
	}
}
