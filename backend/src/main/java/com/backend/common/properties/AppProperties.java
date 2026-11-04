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
	private Agent agent = new Agent();
	private Calendar calendar = new Calendar();
	private Screening screening = new Screening();

	@Getter
	@Setter
	public static class Agent {
		/** false 이면 예약/자동 실행이 모두 멈춘다(운영 비상 스위치). */
		private boolean enabled = true;
		private int maxActiveTasksPerMember = 20;
		/** AI 가 한 번 실행되는 동안 부를 수 있는 도구 호출 수 상한 */
		private int maxToolCalls = 40;
		private int llmTimeoutSeconds = 300;
		/** 연속 실패가 이 횟수에 도달하면 작업을 일시정지한다. */
		private int maxConsecutiveFailures = 5;
		/** 실전 계좌 주문 실행, 일시정지 같은 중요한 일이 생기면 이메일로 알린다. */
		private boolean notifyEmail = true;
		/** 예약 시각을 이 시간 넘겨서 서버가 일어났다면 낡은 지시로 보고 실행하지 않는다. */
		private int maxLatenessMinutes = 30;
	}

	@Getter
	@Setter
	public static class Screening {
		/** false 이면 야간 스크리닝 배치를 돌리지 않고, 추천은 예전처럼 요청 때마다 후보를 실시간 분석한다. */
		private boolean enabled = true;
		/** 한국 유동성 상위 몇 종목까지 분석할지(20일 평균 거래대금 기준) */
		private int topLiquidKrx = 1000;
		private int topLiquidUs = 1000;
		/** 서버가 켜질 때 저장된 결과가 없으면 곧바로 한 번 돌린다(개발 환경에서는 끄는 것을 권장). */
		private boolean runOnStartupIfEmpty = false;
		/** 결과가 이 일수보다 오래되면 낡았다고 보고 실시간 분석으로 대신한다. */
		private int maxAgeDays = 6;
		private int keepDays = 400;
		/** 배치 한 번을 기다릴 최대 시간(분) */
		private int timeoutMinutes = 120;
	}

	@Getter
	@Setter
	public static class Calendar {
		/**
		 * 한국거래소 휴장일(yyyy-MM-dd). 주말과 고정 공휴일(신정, 삼일절, 근로자의 날, 어린이날, 현충일, 광복절,
		 * 개천절, 한글날, 성탄절, 연말)은 코드에 내장되어 있고, 여기에는 음력 명절·선거일·임시공휴일과
		 * 대체공휴일처럼 해마다 달라지는 날을 적는다. 매년 갱신 필요.
		 */
		private List<String> krxHolidays = List.of(
			"2026-02-16", "2026-02-17", "2026-02-18", "2026-05-25", "2026-06-03",
			"2026-08-17", "2026-09-24", "2026-09-25", "2026-10-05",
			"2027-02-08", "2027-02-09", "2027-05-13", "2027-09-14", "2027-09-15", "2027-09-16");
	}

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
		/** 회원가입을 허용할 이메일 목록. 여기에 없는 이메일은 가입이 거절된다. 비어 있으면 아무도 가입할 수 없다. */
		private List<String> allowedSignupEmails = List.of("waldenho0313@gmail.com");
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
