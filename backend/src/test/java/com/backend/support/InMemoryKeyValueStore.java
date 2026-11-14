package com.backend.support;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import com.backend.common.security.guard.KeyValueStore;

/** 테스트용 메모리 저장소. 유효시간은 값이 살아 있는 동안의 남은 초 계산에만 쓰고, 시계를 앞당기는 expire 로 만료를 흉내 낸다. */
public class InMemoryKeyValueStore implements KeyValueStore {

	private record Entry(String value, long expiresAtMillis) {
	}

	private final Map<String, Entry> data = new HashMap<>();
	private long offsetMillis = 0;

	private long now() {
		return System.currentTimeMillis() + offsetMillis;
	}

	/** 시간이 흐른 것처럼 만든다. */
	public void advance(Duration d) {
		offsetMillis += d.toMillis();
	}

	private Entry live(String key) {
		Entry e = data.get(key);
		if (e != null && e.expiresAtMillis() <= now()) {
			data.remove(key);
			return null;
		}
		return e;
	}

	@Override
	public long increment(String key, Duration ttl) {
		return add(key, 1, ttl);
	}

	@Override
	public long add(String key, long delta, Duration ttl) {
		Entry e = live(key);
		long v = (e == null ? 0 : Long.parseLong(e.value())) + delta;
		data.put(key, new Entry(String.valueOf(v), now() + ttl.toMillis()));
		return v;
	}

	@Override
	public long getLong(String key) {
		Entry e = live(key);
		return e == null ? 0 : Long.parseLong(e.value());
	}

	@Override
	public void put(String key, String value, Duration ttl) {
		data.put(key, new Entry(value, now() + ttl.toMillis()));
	}

	@Override
	public Optional<String> get(String key) {
		Entry e = live(key);
		return e == null ? Optional.empty() : Optional.of(e.value());
	}

	@Override
	public long ttlSeconds(String key) {
		Entry e = live(key);
		return e == null ? 0 : Math.max(0, (e.expiresAtMillis() - now()) / 1000);
	}

	@Override
	public void delete(String key) {
		data.remove(key);
	}
}
