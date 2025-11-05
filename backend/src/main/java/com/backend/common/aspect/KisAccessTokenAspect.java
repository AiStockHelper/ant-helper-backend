package com.backend.common.aspect;

import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import com.backend.common.util.memberLoader.MemberLoader;
import com.backend.domains.broker.service.BrokerService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class KisAccessTokenAspect {

	private final BrokerService brokerService;
	private final MemberLoader memberLoader;
}
