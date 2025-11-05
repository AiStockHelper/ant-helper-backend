package com.backend.common.exception;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.backend.common.dto.ErrorResponse;
import com.backend.order.kis.kis_client.exception.KisClientException;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

	@ExceptionHandler(ApiException.class)
	public ResponseEntity<Object> handleApiException(ApiException e) {
		log.warn("handleApiException", e);

		return makeErrorResponseEntityWithReasons(e.getErrorCode());
	}

	// KisClientException 처리 -> KisLibrary 내부 에러
	@ExceptionHandler(KisClientException.class)
	public ResponseEntity<Object> handleKisClientException(KisClientException e) {
		log.warn("handleKisClientException", e);

		// ErrorCode는 KIS_CLIENT_ERROR로 고정하되, 내부 메시지는 KisClientException의 메시지를 사용
		ErrorCode errorCode = ErrorCode.KIS_CLIENT_ERROR;
		return ResponseEntity
			.status(errorCode.getHttpStatus())
			.body(ErrorResponse.of(errorCode, e.getMessage()));
	}

	@Override
	protected ResponseEntity<Object> handleNoResourceFoundException(
		NoResourceFoundException ex,
		HttpHeaders headers,
		HttpStatusCode status,
		WebRequest request) {
		log.warn("handleNoResourceFoundException", ex);

		ErrorCode errorCode = ErrorCode.RESOURCE_NOT_FOUND;

		return makeErrorResponseEntityWithReasons(errorCode);
	}

	@Override
	public ResponseEntity<Object> handleMethodArgumentNotValid(
		MethodArgumentNotValidException e,
		HttpHeaders headers,
		HttpStatusCode status,
		WebRequest request) {
		log.warn("handleIllegalArgument", e);

		List<String> messages = e.getBindingResult().getFieldErrors()
			.stream()
			.map(ex -> ex.getDefaultMessage())
			.collect(Collectors.toList());

		ErrorCode errorCode = ErrorCode.INVALID_PARAMETER;
		return makeErrorResponseEntityWithReasons(errorCode, messages);
	}

	@Override
	protected ResponseEntity<Object> handleHttpMessageNotReadable(
		HttpMessageNotReadableException ex,
		HttpHeaders headers,
		HttpStatusCode status,
		WebRequest request) {
		log.warn("handleHttpMessageNotReadableException", ex);

		ErrorCode errorCode = ErrorCode.BAD_REQUEST;
		return makeErrorResponseEntityWithReasons(errorCode);
	}

	@Override
	protected ResponseEntity<Object> handleHandlerMethodValidationException(
		HandlerMethodValidationException ex,
		HttpHeaders headers,
		HttpStatusCode status,
		WebRequest request) {
		log.warn("handleHandlerMethodValidationException", ex);
		List<String> messages = Arrays.stream(ex.getDetailMessageArguments())
			.map(Object::toString)
			.toList();

		ErrorCode errorCode = ErrorCode.INVALID_PARAMETER;
		return makeErrorResponseEntityWithReasons(errorCode, messages);
	}

	@ExceptionHandler({Exception.class})
	public ResponseEntity<Object> handleAllException(Exception e) {
		log.warn("handleAllException", e);

		ErrorCode errorCode = ErrorCode.INTERNAL_SERVER_ERROR;
		return makeErrorResponseEntityWithReasons(errorCode);
	}

	// ErrorCode를 받아서 Response를 만드는 메서드
	private ResponseEntity<Object> makeErrorResponseEntityWithReasons(ErrorCode errorCode) {
		return ResponseEntity
			.status(errorCode.getHttpStatus())
			.body(ErrorResponse.from(errorCode));
	}

	// ErrorCode와 메시지 리스트를 받아서 Response를 만드는 메서드
	private ResponseEntity<Object> makeErrorResponseEntityWithReasons(ErrorCode errorCode, List<String> message) {
		return ResponseEntity
			.status(errorCode.getHttpStatus())
			.body(ErrorResponse.of(errorCode, message));
	}
}
