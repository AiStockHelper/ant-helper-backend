package com.backend.common.security.audit;

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

/** 로그인·계좌·자동매매 시작 같은 보안상 의미 있는 사건의 기록. 비밀번호·토큰·키 값은 절대 넣지 않는다. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "security_event", indexes = {
	@Index(name = "idx_sec_event_member", columnList = "member_id, occurred_at"),
	@Index(name = "idx_sec_event_time", columnList = "occurred_at")
})
public class SecurityEvent {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "occurred_at", nullable = false)
	private LocalDateTime occurredAt;

	@Column(name = "type", nullable = false, length = 40)
	private String type;

	@Column(name = "member_id")
	private Long memberId;

	@Column(name = "email", length = 120)
	private String email;

	@Column(name = "ip", length = 64)
	private String ip;

	@Column(name = "detail", length = 500)
	private String detail;

	public SecurityEvent(String type, Long memberId, String email, String ip, String detail) {
		this.occurredAt = LocalDateTime.now();
		this.type = type;
		this.memberId = memberId;
		this.email = email == null ? null : (email.length() > 120 ? email.substring(0, 120) : email);
		this.ip = ip;
		this.detail = detail == null ? null : (detail.length() > 500 ? detail.substring(0, 500) : detail);
	}
}
