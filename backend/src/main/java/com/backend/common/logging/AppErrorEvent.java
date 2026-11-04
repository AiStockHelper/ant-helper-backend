package com.backend.common.logging;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 서버에서 실패로 끝난 일(처리되지 않은 예외, KIS 오류, 주문/예약 실패)의 원장.
 * 민원이 들어오면 request_id, member_id, occurred_at 으로 로그 파일과 연결해서 볼 수 있다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "app_error_event", indexes = {
	@Index(name = "idx_error_event_time", columnList = "occurred_at"),
	@Index(name = "idx_error_event_member", columnList = "member_id, occurred_at"),
	@Index(name = "idx_error_event_req", columnList = "request_id")
})
public class AppErrorEvent {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "occurred_at", nullable = false)
	private LocalDateTime occurredAt;

	/** HTTP / ORDER / SCHEDULER / CHAT / ACTION */
	@Column(name = "source", nullable = false, length = 20)
	private String source;

	@Column(name = "request_id", length = 40)
	private String requestId;

	@Column(name = "member_id")
	private Long memberId;

	@Column(name = "account_id")
	private Long accountId;

	@Column(name = "error_code", length = 60)
	private String errorCode;

	@Column(name = "message", length = 1000)
	private String message;

	@Column(name = "exception_class", length = 200)
	private String exceptionClass;

	@Column(name = "stack_top", columnDefinition = "TEXT")
	private String stackTop;

	@Column(name = "detail", length = 1000)
	private String detail;

	public AppErrorEvent(String source, String requestId, Long memberId, Long accountId, String errorCode,
		String message, String exceptionClass, String stackTop, String detail) {
		this.occurredAt = LocalDateTime.now();
		this.source = source;
		this.requestId = requestId;
		this.memberId = memberId;
		this.accountId = accountId;
		this.errorCode = errorCode;
		this.message = message;
		this.exceptionClass = exceptionClass;
		this.stackTop = stackTop;
		this.detail = detail;
	}
}
