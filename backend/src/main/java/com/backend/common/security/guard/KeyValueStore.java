package com.backend.common.security.guard;

import java.time.Duration;
import java.util.Optional;

/** 시도 횟수·인증번호·잠금처럼 짧게 사는 값을 저장하는 최소 저장소. 운영에서는 Redis, 테스트에서는 메모리. */
public interface KeyValueStore {

	/** 값을 1 올리고(없으면 1) 유효시간을 갱신한다. 올린 뒤의 값을 돌려준다. */
	long increment(String key, Duration ttl);

	/** 값에 delta 를 더하고(없으면 0 에서 시작) 유효시간을 갱신한다. 더한 뒤의 값을 돌려준다. */
	long add(String key, long delta, Duration ttl);

	long getLong(String key);

	void put(String key, String value, Duration ttl);

	Optional<String> get(String key);

	/** 남은 유효시간(초). 키가 없으면 0. */
	long ttlSeconds(String key);

	void delete(String key);
}
