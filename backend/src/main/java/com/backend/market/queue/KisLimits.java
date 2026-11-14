package com.backend.market.queue;

/**
 * 한국투자증권(KIS) 호출 한도와 재시도 정책을 한 곳에서 관리하는 전역 상수.
 * KIS 가 한도를 바꾸면 이 파일만 고치면 된다.
 *
 * <p>공식 한도(2026-04 공지 기준, 앱키 단위): 실전 18건/초, 모의 1건/초, 접근토큰 발급 1건/초.
 * 초과하면 HTTP 500 + msg_cd EGW00201 로 거절된다.
 */
public final class KisLimits {

	private KisLimits() {
	}

	// ─────────────────────────── 초당 호출 한도 ───────────────────────────

	public static final int REAL_OFFICIAL_PER_SECOND = 18;
	public static final int PAPER_OFFICIAL_PER_SECOND = 1;

	/** 실제로 쓰는 한도. 공식 한도보다 낮게 잡아 네트워크 지연 때문에 서버에서 몰려 보이는 경우를 흡수한다. */
	public static final int REAL_PER_SECOND = 15;
	public static final int PAPER_PER_SECOND = 1;

	/** 요청 시작 간격 = (1000ms / 한도) × 이 값. 1.10 이면 한도 위로 10% 여유를 둔다. */
	public static final double SPACING_FACTOR = 1.10;

	public static int perSecond(boolean paper) {
		return paper ? PAPER_PER_SECOND : REAL_PER_SECOND;
	}

	/** 같은 앱키의 요청이 시작되는 최소 간격(ms) */
	public static long spacingMillis(boolean paper) {
		return (long)Math.ceil(1000.0 / perSecond(paper) * SPACING_FACTOR);
	}

	// ─────────────────────────── 대기·재시도 ───────────────────────────

	/** 조회 요청이 큐에서 기다릴 수 있는 최대 시간. 넘으면 버리고 호출한 쪽에 실패를 알린다. */
	public static final long LOOKUP_MAX_WAIT_MILLIS = 90_000L;
	/** 주문이 큐에서 기다릴 수 있는 최대 시간 */
	public static final long ORDER_MAX_WAIT_MILLIS = 120_000L;
	/** 초당 한도 초과(EGW00201)를 받았을 때 같은 호출 안에서 다시 보내는 횟수 */
	public static final int LOOKUP_RATE_LIMIT_RETRIES = 1;
	public static final int ORDER_RATE_LIMIT_RETRIES = 3;
	public static final long RATE_LIMIT_RETRY_DELAY_MILLIS = 1_200L;

	// ─────────────────────────── 주문 재시도 ───────────────────────────

	/** 첫 시도가 실패한 뒤 스케줄러가 다시 시도하는 최대 횟수 */
	public static final int ORDER_MAX_RETRIES = 10;
	/** n 번째 재시도 전에 기다리는 시간(초). 마지막 값은 이후 계속 사용. */
	public static final int[] ORDER_RETRY_BACKOFF_SECONDS = {5, 10, 20, 30, 60, 120, 180, 300, 300, 300};
	/** 전송 중(SENDING)으로 이 시간 넘게 멈춰 있으면 서버가 중간에 종료된 것으로 보고 다시 처리 대상으로 돌린다. */
	public static final long STUCK_SENDING_SECONDS = 180L;

	public static int backoffSeconds(int retryCount) {
		int i = Math.max(0, Math.min(retryCount - 1, ORDER_RETRY_BACKOFF_SECONDS.length - 1));
		return ORDER_RETRY_BACKOFF_SECONDS[i];
	}
}
