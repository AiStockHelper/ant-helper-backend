package com.backend.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.backend.common.exception.ApiException;
import com.backend.common.exception.ErrorCode;
import com.backend.common.properties.AppProperties;
import com.backend.common.security.guard.AttemptGuard;
import com.backend.support.InMemoryKeyValueStore;

class AttemptGuardTest {

	private InMemoryKeyValueStore store;
	private AttemptGuard guard;

	@BeforeEach
	void setUp() {
		store = new InMemoryKeyValueStore();
		guard = new AttemptGuard(store, new AppProperties());
	}

	@Test
	void locksAfterTheTenthConsecutiveFailure() {
		for (int i = 1; i < 10; i++) {
			assertThat(guard.fail("k", 10)).isFalse();
			guard.assertOpen("k");
		}
		assertThat(guard.fail("k", 10)).isTrue();
		assertThatThrownBy(() -> guard.assertOpen("k"))
			.isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TOO_MANY_ATTEMPTS));
	}

	@Test
	void successResetsTheCounterAndLifts() {
		for (int i = 0; i < 9; i++) {
			guard.fail("k", 10);
		}
		guard.reset("k");
		assertThat(guard.failures("k")).isZero();
		for (int i = 0; i < 9; i++) {
			assertThat(guard.fail("k", 10)).isFalse();
		}
	}

	@Test
	void lockExpiresAfterTheLockWindow() {
		for (int i = 0; i < 10; i++) {
			guard.fail("k", 10);
		}
		store.advance(Duration.ofMinutes(31));
		guard.assertOpen("k");
	}

	@Test
	void keysAreIndependent() {
		for (int i = 0; i < 10; i++) {
			guard.fail("a", 10);
		}
		guard.assertOpen("b");
	}
}
