package com.backend.common.security.audit;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.extern.slf4j.Slf4j;

/** 보안 사건을 DB 와 로그에 남긴다. 호출한 트랜잭션과 분리해서 롤백돼도 기록은 남고, 기록 실패가 본 흐름을 깨지 않는다. */
@Slf4j
@Component
public class SecurityAudit {

	public static final String LOGIN_SUCCESS = "LOGIN_SUCCESS";
	public static final String LOGIN_PASSWORD_FAILED = "LOGIN_PASSWORD_FAILED";
	public static final String LOGIN_OTP_FAILED = "LOGIN_OTP_FAILED";
	public static final String LOGIN_LOCKED = "LOGIN_LOCKED";
	public static final String STEP_UP_OK = "STEP_UP_OK";
	public static final String STEP_UP_FAILED = "STEP_UP_FAILED";
	public static final String ACCOUNT_ADDED = "ACCOUNT_ADDED";
	public static final String ACCOUNT_DELETED = "ACCOUNT_DELETED";
	public static final String AGENT_STARTED = "AGENT_STARTED";
	public static final String ORDER_APPROVED = "ORDER_APPROVED";
	public static final String REFRESH_REUSE = "REFRESH_REUSE_DETECTED";
	public static final String LOGOUT = "LOGOUT";

	private final SecurityEventRepository repository;
	private final TransactionTemplate tx;

	public SecurityAudit(SecurityEventRepository repository, PlatformTransactionManager txManager) {
		this.repository = repository;
		this.tx = new TransactionTemplate(txManager);
		this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	public void record(String type, Long memberId, String email, String ip, String detail) {
		log.info("SECURITY {} member={} ip={} {}", type, memberId, ip, detail == null ? "" : detail);
		try {
			tx.executeWithoutResult(s -> repository.save(new SecurityEvent(type, memberId, email, ip, detail)));
		} catch (RuntimeException e) {
			log.warn("보안 이벤트 기록 실패 type={}: {}", type, e.toString());
		}
	}
}
