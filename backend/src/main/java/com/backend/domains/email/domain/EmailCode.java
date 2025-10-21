package com.backend.domains.email.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.redis.core.RedisHash;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Getter
@AllArgsConstructor
@RedisHash(value = "emailCode", timeToLive = 5 * 60)
public class EmailCode {

	@Id
	private String email;

	@Setter
	private Integer code;
}
