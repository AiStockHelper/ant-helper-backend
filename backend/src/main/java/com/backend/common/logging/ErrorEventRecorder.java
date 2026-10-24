package com.backend.common.logging;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.extern.slf4j.Slf4j;

/**
 * 실패를 app_error_event 에 남긴다. 호출한 트랜잭션과 분리(REQUIRES_NEW)해서
 * 호출자가 롤백돼도 기록은 남고, 기록이 실패해도 본래 흐름을 깨지 않는다.
 */
@Slf4j
@Component
public class ErrorEventRecorder {

	private static final int MAX_STACK_LINES = 12;

	private final AppErrorEventRepository repository;
	private final TransactionTemplate tx;

	public ErrorEventRecorder(AppErrorEventRepository repository, PlatformTransactionManager txManager) {
		this.repository = repository;
		this.tx = new TransactionTemplate(txManager);
		this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	public void record(String source, Long accountId, String errorCode, String message, Throwable error, String detail) {
		try {
			String reqId = MDC.get(RequestLogFilter.REQ_ID);
			Long memberId = parseLong(MDC.get(RequestLogFilter.MEMBER_ID));
			AppErrorEvent event = new AppErrorEvent(source, reqId, memberId, accountId, cut(errorCode, 60),
				cut(message, 1000), error == null ? null : error.getClass().getName(), stackTop(error), cut(detail, 1000));
			tx.executeWithoutResult(s -> repository.save(event));
		} catch (RuntimeException e) {
			log.warn("오류 원장 기록에 실패했습니다: {}", e.getMessage());
		}
	}

	public void record(String source, String errorCode, String message, Throwable error) {
		record(source, null, errorCode, message, error, null);
	}

	private static String stackTop(Throwable error) {
		if (error == null) {
			return null;
		}
		StringBuilder sb = new StringBuilder(error.toString());
		StackTraceElement[] st = error.getStackTrace();
		for (int i = 0; i < Math.min(st.length, MAX_STACK_LINES); i++) {
			sb.append("\n\tat ").append(st[i]);
		}
		Throwable cause = error.getCause();
		if (cause != null && cause != error) {
			sb.append("\nCaused by: ").append(cause);
			StackTraceElement[] cs = cause.getStackTrace();
			for (int i = 0; i < Math.min(cs.length, 5); i++) {
				sb.append("\n\tat ").append(cs[i]);
			}
		}
		return sb.toString();
	}

	private static String cut(String s, int max) {
		if (s == null) {
			return null;
		}
		return s.length() <= max ? s : s.substring(0, max);
	}

	private static Long parseLong(String s) {
		try {
			return s == null ? null : Long.valueOf(s);
		} catch (NumberFormatException e) {
			return null;
		}
	}
}
