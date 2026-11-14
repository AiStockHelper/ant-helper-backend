package com.backend.common.security.guard;

import java.time.Duration;

import org.springframework.stereotype.Component;

import com.backend.common.exception.ApiException;
import com.backend.common.exception.ErrorCode;
import com.backend.common.properties.AppProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 연속 실패 횟수를 세고, 한도를 넘으면 일정 시간 잠근다. 성공하면 reset 으로 초기화한다.
 * 키는 호출하는 쪽이 정한다(예: "login:email:a@b.c", "login:ip:1.2.3.4").
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AttemptGuard {

	private static final String FAIL = "guard:fail:";
	private static final String LOCK = "guard:lock:";

	private final KeyValueStore store;
	private final AppProperties props;

	/** 잠겨 있으면 예외. */
	public void assertOpen(String key) {
		if (store.get(LOCK + key).isPresent()) {
			long sec = Math.max(1, store.ttlSeconds(LOCK + key));
			throw ApiException.of(ErrorCode.TOO_MANY_ATTEMPTS,
				"시도 횟수를 초과해 잠시 잠겼습니다. 약 " + ((sec + 59) / 60) + "분 뒤에 다시 시도해 주세요.");
		}
	}

	/** 실패를 기록한다. limit 에 도달하면 잠그고 true 를 돌려준다. */
	public boolean fail(String key, int limit) {
		Duration window = Duration.ofMinutes(props.getSecurity().getLockMinutes());
		long n = store.increment(FAIL + key, window.multipliedBy(2));
		if (n >= limit) {
			store.put(LOCK + key, "1", window);
			store.delete(FAIL + key);
			log.warn("연속 실패 {}회로 잠금: key={}", n, mask(key));
			return true;
		}
		return false;
	}

	public long failures(String key) {
		return store.getLong(FAIL + key);
	}

	public void reset(String key) {
		store.delete(FAIL + key);
		store.delete(LOCK + key);
	}

	private static String mask(String key) {
		int at = key.indexOf('@');
		return at > 2 ? key.substring(0, at - 2) + "**" + key.substring(at) : key;
	}
}
