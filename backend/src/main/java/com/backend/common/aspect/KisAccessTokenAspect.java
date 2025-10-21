package com.backend.common.aspect;

import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.stereotype.Component;

import com.backend.common.util.memberLoader.MemberLoader;
import com.backend.domains.broker.service.BrokerService;
import com.backend.domains.member.domain.Member;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class KisAccessTokenAspect {

	private final BrokerService brokerService;
	private final MemberLoader memberLoader;

	@Before("execution(* com.backend.domains.stock.service.*.*(..))")
	public void issueKisAccessTokenBefore() {
		log.info("Issue Kis Access Token Before");
		Member member = memberLoader.getMember();

		brokerService.getAndSaveAccessToken(member);
	}
}
