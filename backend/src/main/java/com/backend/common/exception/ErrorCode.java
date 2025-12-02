package com.backend.common.exception;

import org.springframework.http.HttpStatus;

import lombok.Getter;

@Getter
public enum ErrorCode {

	// 공통 예외
	BAD_REQUEST(HttpStatus.BAD_REQUEST, "잘못된 요청입니다.", "C-001"),
	INVALID_PARAMETER(HttpStatus.BAD_REQUEST, "요청 파라미터가 잘 못 되었습니다.", "C-002"),
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
	REISSUE_ACCESS_TOKEN(HttpStatus.PAYMENT_REQUIRED, "액세스 토큰 재발급이 필요합니다.", "S-005"),

	// Member 예외
	MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "해당하는 회원을 찾을 수 없습니다.", "M-001"),
	EMAIL_DUPLICATE(HttpStatus.CONFLICT, "이미 가입된 이메일입니다.", "M-002"),

	// MemberAccount 예외
	MEMBER_ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND, "해당하는 회원 계좌를 찾을 수 없습니다.", "MA-001"),
	DUPLICATE_MEMBER_ACCOUNT(HttpStatus.CONFLICT, "이미 사용되는 회원 계좌입니다.", "MA-024"),

	// Transaction 예외
	BALANCE_NOT_ENOUGH_EXCEPTION(HttpStatus.BAD_REQUEST, "잔액이 부족합니다.", "T-001"),
	EFFECTIVE_AT_CANT_NOT_BE_NULL(HttpStatus.BAD_REQUEST, "거래의 effectiveAt은 null일 수 없습니다.", "T-002"),
	DUPLICATED_DISCARD_EXCEPTION(HttpStatus.CONFLICT, "이미 폐기된 거래입니다.", "T-003"),
	ACCOUNT_NOT_FOUND_EXCEPTION(HttpStatus.NOT_FOUND, "거래 계좌를 찾을 수 없습니다.", "T-004"),
	COIN_TRANSFER_SAME_USER_EXCEPTION(HttpStatus.BAD_REQUEST, "자기 자신에게 코인을 양도할 수 없습니다.", "T-005"),
	COIN_TRANSFER_AMOUNT_INVALID_EXCEPTION(HttpStatus.BAD_REQUEST, "코인 양도 금액은 0보다 커야 합니다.", "T-006"),

	// AiChat 예외
	AI_CHAT_ROOM_NOT_FOUND(HttpStatus.NOT_FOUND, "해당하는 AI 채팅방을 찾을 수 없습니다.", "AC-001"),
	AI_CHAT_MESSAGE_ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "해당하는 AI 채팅 메시지 순서 정보를 찾을 수 없습니다.", "AC-002"),
	AI_CHAT_ROOM_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 AI 채팅방이 존재합니다.", "AC-003"),
	AI_CHAT_MESSAGE_NOT_FOUND(HttpStatus.NOT_FOUND, "해당하는 AI 채팅 메시지를 찾을 수 없습니다.", "AC-004"),
	AI_CHAT_CANNOT_CANCEL(HttpStatus.BAD_REQUEST, "요청 중인 메시지만 취소할 수 있습니다.", "AC-005"),

	// WatchList 예외
	TOO_MANY_WATCH_LIST(HttpStatus.TOO_MANY_REQUESTS, "관심 목록의 최대 개수를 초과하였습니다.", "W-001"),

	// 캡차 기능 예외
	CAPTCHA_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "캡차 서버에서 에러가 발생하였습니다.", "CS-001"),

	// 한국 투자 증권 관련 요류
	KIS_ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND, "해당하는 계좌를 찾을 수 없습니다.", "KIS-001"),
	KIS_CLIENT_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "요청에서 오류가 발생하였습니다.", "KIS-002"),
	KIS_PAPER_ACCOUNT_CANT_USE(HttpStatus.BAD_REQUEST, "모의투자 계좌는 해당 기능을 사용할 수 없습니다.", "KIS-003"),
	TRANSACTION_NOT_FOUND(HttpStatus.NOT_FOUND, "해당하는 거래를 찾을 수 없습니다.", "KIS-005"),
	TRANSACTION_DUPLICATE(HttpStatus.CONFLICT, "이미 거래가 존재합니다", "KIS-007"),
	PRODUCT_NUMBER_DUPLICATE(HttpStatus.CONFLICT, "Product Number가 중복됩니다.", "KIS-008"),

	// 기타
	AUTO_TRADE_STATE_OFF(HttpStatus.INTERNAL_SERVER_ERROR, "자동 거래 상태가 꺼져 있습니다.", "AT-001"),
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
