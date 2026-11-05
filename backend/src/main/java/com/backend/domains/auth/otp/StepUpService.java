package com.backend.domains.auth.otp;

import java.time.Duration;

import org.springframework.stereotype.Service;

import com.backend.common.exception.ApiException;
import com.backend.common.exception.ErrorCode;
import com.backend.common.properties.AppProperties;
import com.backend.common.security.audit.SecurityAudit;
import com.backend.common.security.guard.AttemptGuard;
import com.backend.common.security.guard.KeyValueStore;
import com.backend.domains.auth.otp.OtpService.Issued;
import com.backend.domains.auth.otp.OtpService.Purpose;
import com.backend.domains.member.domain.Member;
import com.backend.domains.member.repository.MemberRepository;

import lombok.RequiredArgsConstructor;

/**
 * 실계좌 등록, 실전 주문 승인, 자동매매 시작 같은 민감한 작업 직전에 이메일 인증번호를 한 번 더 확인한다.
 * 인증에 성공하면 stepUpMinutes 동안 같은 회원의 민감한 작업을 통과시킨다.
 */
@Service
@RequiredArgsConstructor
public class StepUpService {

	private final OtpService otp;
	private final KeyValueStore store;
	private final AttemptGuard guard;
	private final MemberRepository members;
	private final AppProperties props;
	private final SecurityAudit audit;

	public Issued request(Long memberId, String ip) {
		Member m = members.findById(memberId).orElseThrow(() -> ApiException.from(ErrorCode.MEMBER_NOT_FOUND));
		guard.assertOpen(guardKey(memberId));
		return otp.issue(Purpose.STEP_UP, String.valueOf(memberId), m.getEmail(), ip);
	}

	public void verify(Long memberId, String challengeId, String code, String ip) {
		String key = guardKey(memberId);
		guard.assertOpen(key);
		String subject = otp.peekSubject(Purpose.STEP_UP, challengeId).orElse(null);
		if (subject == null || !subject.equals(String.valueOf(memberId))) {
			throw ApiException.from(ErrorCode.INVALID_OTP);
		}
		try {
			otp.verify(Purpose.STEP_UP, challengeId, code);
		} catch (ApiException e) {
			audit.record(SecurityAudit.STEP_UP_FAILED, memberId, null, ip, null);
			guard.fail(key, props.getSecurity().getMaxLoginFailures());
			throw e;
		}
		guard.reset(key);
		Member m = members.findById(memberId).orElse(null);
		otp.clearSendCounters(m == null ? "" : m.getEmail(), ip);
		store.put(grantKey(memberId), "1", Duration.ofMinutes(props.getSecurity().getStepUpMinutes()));
		audit.record(SecurityAudit.STEP_UP_OK, memberId, null, ip, null);
	}

	public boolean isGranted(Long memberId) {
		return store.get(grantKey(memberId)).isPresent();
	}

	/** 통과한 적이 없으면 STEP_UP_REQUIRED. 클라이언트는 이 코드를 받으면 인증번호 입력창을 띄운다. */
	public void require(Long memberId) {
		if (!isGranted(memberId)) {
			throw ApiException.from(ErrorCode.STEP_UP_REQUIRED);
		}
	}

	public int validMinutes() {
		return props.getSecurity().getStepUpMinutes();
	}

	private static String grantKey(Long memberId) {
		return "stepup:" + memberId;
	}

	private static String guardKey(Long memberId) {
		return "stepup-fail:" + memberId;
	}
}
