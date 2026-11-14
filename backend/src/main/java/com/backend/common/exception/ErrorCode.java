package com.backend.common.exception;

import org.springframework.http.HttpStatus;

import lombok.Getter;

@Getter
public enum ErrorCode {

	// 공통 예외
	BAD_REQUEST(HttpStatus.BAD_REQUEST, "잘못된 요청입니다.", "C-001"),
	INVALID_PARAMETER(HttpStatus.BAD_REQUEST, "요청 파라미터가 잘못되었습니다.", "C-002"),
	RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "해당 리소스를 찾을 수 없습니다.", "C-003"),
	INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부에서 에러가 발생하였습니다.", "C-004"),
	FORBIDDEN(HttpStatus.FORBIDDEN, "해당 권한이 없습니다.", "C-005"),

	// 이메일 기능 예외
	INVALID_EMAIL_CODE(HttpStatus.BAD_REQUEST, "유효하지 않은 이메일 코드입니다.", "E-001"),
	EMAIL_CODE_NOT_FOUND(HttpStatus.NOT_FOUND, "해당 이메일 코드를 찾을 수 없습니다.", "E-002"),
	EMAIL_BAD_GATEWAY(HttpStatus.BAD_GATEWAY, "이메일 전송에 실패하였습니다.", "E-003"),

	// 인증 예외
	UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다.", "S-001"),
	UNAUTHENTICATED_EMAIL(HttpStatus.UNAUTHORIZED, "이메일 인증이 필요합니다.", "S-002"),
	INVALID_ACCESS_TOKEN(HttpStatus.UNAUTHORIZED, "유효하지 않은 액세스 토큰입니다.", "S-003"),
	INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "유효하지 않은 리프레시 토큰입니다.", "S-004"),
	REISSUE_ACCESS_TOKEN(HttpStatus.UNAUTHORIZED, "액세스 토큰이 만료되었습니다. 재발급이 필요합니다.", "S-005"),
	INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다.", "S-006"),
	SIGNUP_NOT_ALLOWED(HttpStatus.FORBIDDEN, "가입이 허용되지 않은 이메일입니다. 현재는 승인된 사용자만 가입할 수 있습니다.", "S-007"),
	TOO_MANY_ATTEMPTS(HttpStatus.TOO_MANY_REQUESTS, "시도 횟수를 초과했습니다. 잠시 후 다시 시도해 주세요.", "S-008"),
	INVALID_OTP(HttpStatus.UNAUTHORIZED, "인증번호가 올바르지 않거나 만료되었습니다.", "S-009"),
	STEP_UP_REQUIRED(HttpStatus.FORBIDDEN, "실계좌 보호를 위해 이메일 인증번호 확인이 필요합니다.", "S-010"),
	DAILY_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "계좌의 하루 주문 한도를 초과했습니다.", "S-011"),

	// Member 예외
	MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "해당하는 회원을 찾을 수 없습니다.", "M-001"),
	EMAIL_DUPLICATE(HttpStatus.CONFLICT, "이미 가입된 이메일입니다.", "M-002"),

	// MemberAccount 예외
	MEMBER_ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND, "해당하는 회원 계좌를 찾을 수 없습니다.", "MA-001"),
	DUPLICATE_MEMBER_ACCOUNT(HttpStatus.CONFLICT, "이미 사용되는 회원 계좌입니다.", "MA-024"),

	// LLM 예외
	AI_REQUEST_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "AI 요청 처리에 실패하였습니다.", "LLM-001"),

	// 캡차 기능 예외
	CAPTCHA_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "캡차 서버에서 에러가 발생하였습니다.", "CS-001"),

	// 한국 투자 증권 관련 요류
	KIS_ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND, "해당하는 계좌를 찾을 수 없습니다.", "KIS-001"),
	KIS_CLIENT_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "요청에서 오류가 발생하였습니다.", "KIS-002"),
	KIS_PAPER_ACCOUNT_CANT_USE(HttpStatus.BAD_REQUEST, "모의투자 계좌는 해당 기능을 사용할 수 없습니다.", "KIS-003"),

	// 채팅
	CONVERSATION_NOT_FOUND(HttpStatus.NOT_FOUND, "대화를 찾을 수 없습니다.", "CH-001"),
	CHAT_MESSAGE_NOT_FOUND(HttpStatus.NOT_FOUND, "메시지를 찾을 수 없습니다.", "CH-002"),
	ACTION_NOT_FOUND(HttpStatus.NOT_FOUND, "확인 요청을 찾을 수 없습니다.", "CH-003"),
	ACTION_NOT_PENDING(HttpStatus.CONFLICT, "이미 처리되었거나 만료된 확인 요청입니다.", "CH-004"),
	CHAT_EMPTY_MESSAGE(HttpStatus.BAD_REQUEST, "메시지 내용이 비어 있습니다.", "CH-005"),
	CHAT_STREAM_BUSY(HttpStatus.CONFLICT, "이 대화는 이미 답변을 생성 중입니다.", "CH-006"),

	// 시세 / 거래
	STOCK_NOT_FOUND(HttpStatus.NOT_FOUND, "종목을 찾을 수 없습니다.", "MK-001"),
	MARKET_NOT_SUPPORTED(HttpStatus.BAD_REQUEST, "지원하지 않는 시장입니다.", "MK-002"),
	NO_ACCOUNT_SELECTED(HttpStatus.BAD_REQUEST, "사용할 증권 계좌가 없습니다. 먼저 계좌를 등록해 주세요.", "MK-003"),
	PREDICTION_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "예측 서버를 사용할 수 없습니다.", "MK-004"),
	NOT_ENOUGH_CANDLES(HttpStatus.UNPROCESSABLE_ENTITY, "예측에 필요한 시세 데이터가 부족합니다.", "MK-005"),
	ORDER_LIMIT_EXCEEDED(HttpStatus.BAD_REQUEST, "1회 주문 한도를 초과했습니다.", "TR-001"),
	ORDER_INVALID(HttpStatus.BAD_REQUEST, "주문 정보가 올바르지 않습니다.", "TR-002"),

	// 자동 실행(에이전트) / 스킬
	AGENT_TASK_NOT_FOUND(HttpStatus.NOT_FOUND, "자동 실행 작업을 찾을 수 없습니다.", "AG-001"),
	AGENT_INVALID(HttpStatus.BAD_REQUEST, "자동 실행 설정이 올바르지 않습니다.", "AG-002"),
	AGENT_LIMIT(HttpStatus.CONFLICT, "자동 실행 작업 개수 한도를 초과했습니다.", "AG-003"),
	AGENT_CONFIRM_REQUIRED(HttpStatus.BAD_REQUEST, "실전 계좌 자동 매매는 확인 절차가 필요합니다.", "AG-004"),
	AGENT_STATE_CONFLICT(HttpStatus.CONFLICT, "현재 상태에서는 할 수 없는 작업입니다.", "AG-005"),
	SKILL_NOT_FOUND(HttpStatus.NOT_FOUND, "스킬을 찾을 수 없습니다.", "AG-006"),
	AGENT_DISABLED(HttpStatus.SERVICE_UNAVAILABLE, "자동 실행 기능이 서버 설정으로 꺼져 있습니다.", "AG-007"),

	;

	private final HttpStatus httpStatus;
	private final String message;
	private final String code;

	ErrorCode(HttpStatus httpStatus, String message, String code) {
		this.httpStatus = httpStatus;
		this.message = message;
		this.code = code;
	}
}
