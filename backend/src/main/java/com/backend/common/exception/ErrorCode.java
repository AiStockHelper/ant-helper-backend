package com.backend.common.exception;

import org.springframework.http.HttpStatus;

import lombok.Getter;

@Getter
public enum ErrorCode {

	//400
	BAD_REQUEST(HttpStatus.BAD_REQUEST, "잘못된 요청입니다."),
	INVALID_PARAMETER(HttpStatus.BAD_REQUEST, "요청 파라미터가 잘 못 되었습니다."),
	INVALID_KEY(HttpStatus.BAD_REQUEST, "유효하지 않은 키입니다."),
	INVALID_EMAIL_CODE(HttpStatus.BAD_REQUEST, "유효하지 않은 이메일 코드입니다."),

	//401
	UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
	UNAUTHENTICATED_EMAIL(HttpStatus.UNAUTHORIZED, "이메일 인증이 필요합니다."),
	INVALID_ACCESS_TOKEN(HttpStatus.UNAUTHORIZED, "유효하지 않은 액세스 토큰입니다."),
	INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "유효하지 않은 리프레시 토큰입니다."),

	//402
	REISSUE_ACCESS_TOKEN(HttpStatus.PAYMENT_REQUIRED, "액세스 토큰 재발급이 필요합니다."),

	//404
	MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "해당하는 회원을 찾을 수 없습니다."),
	RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "해당 리소스를 찾을 수 없습니다."),
	EMAIL_CODE_NOT_FOUND(HttpStatus.NOT_FOUND, "해당 이메일 코드를 찾을 수 없습니다."),
	APP_KEY_OR_SECRET_KEY_NOT_FOUND(HttpStatus.NOT_FOUND, "해당하는 앱키 또는 시크릿키를 찾을 수 없습니다."),
	KIS_ACCESS_TOKEN_NOT_FOUND(HttpStatus.NOT_FOUND, "한국 투자 증권 api의 액세스 토큰을 찾을 수 없습니다."),
	TRANSACTION_NOT_FOUND(HttpStatus.NOT_FOUND, "해당하는 거래를 찾을 수 없습니다."),
	STOCK_NOT_FOUND(HttpStatus.NOT_FOUND, "해당하는 주식을 찾을 수 없습니다."),

	//409
	EMAIL_DUPLICATE(HttpStatus.CONFLICT, "이미 가입된 이메일입니다."),
	TRANSACTION_DUPLICATE(HttpStatus.CONFLICT, "이미 거래가 존재합니다"),
	PRODUCT_NUMBER_DUPLICATE(HttpStatus.CONFLICT, "Product Number가 중복됩니다."),

	//429
	TOO_MANY_WATCH_LIST(HttpStatus.TOO_MANY_REQUESTS, "관심 목록의 최대 개수를 초과하였습니다."),

	//500
	INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부에서 에러가 발생하였습니다."),
	AUTO_TRADE_STATE_OFF(HttpStatus.INTERNAL_SERVER_ERROR, "자동 거래 상태가 꺼져 있습니다."),
	CAPTCHA_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "캡차 서버에서 에러가 발생하였습니다."),

	//502
	EMAIL_BAD_GATEWAY(HttpStatus.BAD_GATEWAY, "이메일 전송에 실패하였습니다."),

	// 권한 오류
	FORBIDDEN(HttpStatus.FORBIDDEN, "해당 권한이 없습니다."),

	// Member 관련 오류

	// MemberAccount 관련 오류
	MEMBER_ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND, "해당하는 회원 계좌를 찾을 수 없습니다."),
	DUPLICATE_MEMBER_ACCOUNT(HttpStatus.CONFLICT, "이미 사용되는 회원 계좌입니다."),

	// 한국 투자 증권 관련 요류
	KIS_ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND, "해당하는 계좌를 찾을 수 없습니다."),
	KIS_CLIENT_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "요청에서 오류가 발생하였습니다.");


	private final HttpStatus httpStatus;
	private final String message;

	ErrorCode(HttpStatus httpStatus, String message) {
		this.httpStatus = httpStatus;
		this.message = message;
	}
}
