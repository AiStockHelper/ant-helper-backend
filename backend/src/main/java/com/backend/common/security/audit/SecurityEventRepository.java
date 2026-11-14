package com.backend.common.security.audit;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SecurityEventRepository extends JpaRepository<SecurityEvent, Long> {

	List<SecurityEvent> findTop50ByMemberIdOrderByIdDesc(Long memberId);

	/** 이 이메일·회원의 직전 성공 로그인에서 보지 못한 IP 인지 확인하는 데 쓴다. */
	boolean existsByMemberIdAndTypeAndIp(Long memberId, String type, String ip);

	long deleteByOccurredAtBefore(LocalDateTime time);
}
