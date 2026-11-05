package com.backend.common.security.guard;

import java.time.Duration;
import java.util.Optional;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class RedisKeyValueStore implements KeyValueStore {

	private final StringRedisTemplate redis;

	@Override
	public long increment(String key, Duration ttl) {
		Long v = redis.opsForValue().increment(key);
		redis.expire(key, ttl);
		return v == null ? 0 : v;
	}

	@Override
	public long add(String key, long delta, Duration ttl) {
		Long v = redis.opsForValue().increment(key, delta);
		redis.expire(key, ttl);
		return v == null ? 0 : v;
	}

	@Override
	public long getLong(String key) {
		String v = redis.opsForValue().get(key);
		try {
			return v == null ? 0 : Long.parseLong(v);
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	@Override
	public void put(String key, String value, Duration ttl) {
		redis.opsForValue().set(key, value, ttl);
	}

	@Override
	public Optional<String> get(String key) {
		return Optional.ofNullable(redis.opsForValue().get(key));
	}

	@Override
	public long ttlSeconds(String key) {
		Long t = redis.getExpire(key, java.util.concurrent.TimeUnit.SECONDS);
		return t == null || t < 0 ? 0 : t;
	}

	@Override
	public void delete(String key) {
		redis.delete(key);
	}
}
