package com.backend.market.queue;

import com.backend.order.kis.kis_client.exception.InvalidApiRequestException;
import com.backend.order.kis.kis_client.exception.KisClientException;

/** KIS 호출 실패를 '다시 보내도 되는가'로 분류한다. */
public enum KisFailure {

	/** 초당 호출 한도 초과(EGW00201). 요청은 처리되지 않았다. */
	RATE_LIMIT(true, false),
	/** 접근토큰 문제(EGW00121/00123/00133). 요청은 처리되지 않았다. */
	TOKEN(true, false),
	/** 응답을 받지 못함(연결/타임아웃). 요청이 KIS 에서 처리됐는지 알 수 없다. */
	NETWORK(true, true),
	/** KIS 서버 오류(5xx). 처리됐는지 알 수 없다. */
	SERVER(true, true),
	/** KIS 가 내용을 보고 거절(잔고 부족, 장 시간 아님, 수량 오류 등). 그대로 다시 보내도 같은 결과다. */
	REJECTED(false, false);

	private final boolean transientError;
	private final boolean ambiguous;

	KisFailure(boolean transientError, boolean ambiguous) {
		this.transientError = transientError;
		this.ambiguous = ambiguous;
	}

	public boolean isTransient() {
		return transientError;
	}

	/** 이미 처리됐을 수도 있어서, 다시 보내기 전에 주문 내역을 확인해야 하는가 */
	public boolean isAmbiguous() {
		return ambiguous;
	}

	public static KisFailure classify(Throwable t) {
		String msg = t == null || t.getMessage() == null ? "" : t.getMessage();
		if (msg.contains("EGW00201")) {
			return RATE_LIMIT;
		}
		if (msg.contains("EGW00121") || msg.contains("EGW00123") || msg.contains("EGW00133")) {
			return TOKEN;
		}
		if (t instanceof InvalidApiRequestException i) {
			if (i.getStatusCode() >= 500) {
				return SERVER;
			}
			if (i.getStatusCode() >= 400) {
				return REJECTED;
			}
			return NETWORK;
		}
		if (t instanceof KisClientException) {
			if (msg.startsWith("Unknown error occurred") || msg.contains("요청이 너무 오래 걸려") || msg.contains("요청이 중단")) {
				return NETWORK;
			}
			if (msg.contains("대기 시간이 초과되어 버렸습니다")) {
				// 줄에서 기다리다 버려졌다면 KIS 로 나가지도 않았다.
				return RATE_LIMIT;
			}
			return REJECTED;
		}
		return NETWORK;
	}
}
