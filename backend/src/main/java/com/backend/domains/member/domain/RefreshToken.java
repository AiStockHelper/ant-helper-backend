package com.backend.domains.member.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.redis.core.RedisHash;
import org.springframework.data.redis.core.TimeToLive;
import org.springframework.data.redis.core.index.Indexed;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** 리프레시 토큰은 SHA-256 해시로만 저장한다. DB/Redis 가 유출돼도 토큰으로 쓸 수 없다. */
@Getter
@AllArgsConstructor
@RedisHash(value = "refreshTokenV2")
public class RefreshToken {

	@Id
	private String tokenHash;

	@Indexed
	private Long memberId;

	@TimeToLive
	private Long ttlSeconds;
}
