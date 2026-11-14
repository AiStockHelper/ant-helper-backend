package com.backend.domains.member.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.redis.core.RedisHash;
import org.springframework.data.redis.core.TimeToLive;
import org.springframework.data.redis.core.index.Indexed;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** 로그아웃한 액세스 토큰의 해시. 토큰 원문은 저장하지 않는다. */
@Getter
@AllArgsConstructor
@RedisHash(value = "blacklistV2")
public class LogoutToken {

	@Id
	private String id;

	@Indexed
	private String tokenHash;

	@TimeToLive
	private Long ttlSeconds;
}
