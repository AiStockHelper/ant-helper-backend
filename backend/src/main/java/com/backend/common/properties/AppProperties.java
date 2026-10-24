package com.backend.common.properties;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@ConfigurationProperties(prefix = "app")
public class AppProperties {

	private Auth auth = new Auth();
	private Prediction prediction = new Prediction();
	private Trading trading = new Trading();
	private Markets markets = new Markets();
	private Llm llm = new Llm();
	private Recommendation recommendation = new Recommendation();
	private Mcp mcp = new Mcp();

	@Getter
	@Setter
	public static class Mcp {
		/**
		 * MCP 도구는 별도 스레드에서 실행되어 호출자를 알 수 없는 경우가 있다. 그때 시세 조회에 사용할 시스템 계좌 ID.
		 * 시세·예측처럼 공개 시장 데이터만 노출하며, 0 이면 사용하지 않는다.
		 */
		private long accountId = 0;
	}

	@Getter
	@Setter
	public static class Auth {
		private boolean requireEmailVerification = false;
	}

	@Getter
	@Setter
	public static class Prediction {
		private String baseUrl = "http://127.0.0.1:8000";
		private int timeoutSeconds = 40;
	}

	@Getter
	@Setter
	public static class Trading {
		private long maxOrderAmountKrw = 10_000_000L;
		private long maxOrderAmountUsd = 10_000L;
		private BigDecimal usLimitSlippagePct = new BigDecimal("0.3");
		private int actionTtlMinutes = 10;
		/** 개발/테스트 전용. true 이면 장 운영 시간 검사를 건너뛴다. 운영에서는 반드시 false. */
		private boolean skipMarketHoursCheck = false;
	}

	@Getter
	@Setter
	public static class Markets {
		private List<String> enabled = List.of("KRX", "NASDAQ", "NYSE", "AMEX");
	}

	@Getter
	@Setter
	public static class Llm {
		private int streamTimeoutSeconds = 180;
		/** LLM 에 보내는 최근 대화 메시지 개수 상한 */
		private int historyLimit = 60;
		/** 최근 대화 창의 글자 수 예산. 넘으면 오래된 메시지부터 빠지고(요약으로 대체됨) 최신 대화가 우선한다. */
		private int historyCharBudget = 14000;
		/** 요약되지 않은 오래된 대화가 이 글자 수를 넘으면 요약을 갱신한다. */
		private int summaryTriggerChars = 9000;
		/** 요약하지 않고 원문 그대로 남겨 두는 가장 최근 메시지 수 */
		private int keepRecentMessages = 8;
		/** 요약문 최대 길이 */
		private int summaryMaxChars = 2200;
	}

	@Getter
	@Setter
	public static class Recommendation {
		private List<String> krxUniverse = List.of();
		private List<String> usUniverse = List.of();
		private int maxCandidatesPaper = 12;
		private int maxCandidatesReal = 25;
	}
}
