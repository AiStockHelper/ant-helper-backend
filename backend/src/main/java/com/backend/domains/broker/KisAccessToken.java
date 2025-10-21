package com.backend.domains.broker;

import org.springframework.data.annotation.Id;
import org.springframework.data.redis.core.RedisHash;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Getter
@AllArgsConstructor
@RedisHash(value = "kisAccessToken", timeToLive = 86400)
public class KisAccessToken {
	@Id
	private Long memberId;

	@Setter
	private String accessToken;
}
