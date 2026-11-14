package com.backend.market.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.backend.market.queue.KisLane.Priority;
import com.backend.order.kis.kis_client.exception.InvalidApiRequestException;
import com.backend.order.kis.kis_client.exception.KisClientException;

class KisLaneTest {

	private KisLane lane;
	private final ExecutorService callers = Executors.newCachedThreadPool();

	@AfterEach
	void tearDown() {
		if (lane != null) {
			lane.close();
		}
		callers.shutdownNow();
	}

	@Test
	void requestsStartNoFasterThanTheSpacing() throws Exception {
		lane = new KisLane("t1", 80);
		List<Long> starts = Collections.synchronizedList(new ArrayList<>());
		List<Future<Integer>> futures = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			final int n = i;
			futures.add(callers.submit(() -> lane.call(Priority.LOOKUP, 10_000, () -> {
				starts.add(System.nanoTime());
				return n;
			})));
		}
		for (Future<Integer> f : futures) {
			f.get(10, TimeUnit.SECONDS);
		}
		List<Long> sorted = starts.stream().sorted().toList();
		for (int i = 1; i < sorted.size(); i++) {
			long gapMs = TimeUnit.NANOSECONDS.toMillis(sorted.get(i) - sorted.get(i - 1));
			assertThat(gapMs).as("gap %d", i).isGreaterThanOrEqualTo(70);
		}
	}

	@Test
	void ordersJumpAheadOfQueuedLookups() throws Exception {
		lane = new KisLane("t2", 150);
		List<String> order = Collections.synchronizedList(new ArrayList<>());
		// 첫 요청이 줄을 점유하는 동안 나머지를 쌓는다.
		Future<?> first = callers.submit(() -> lane.call(Priority.LOOKUP, 10_000, () -> order.add("first")));
		first.get(5, TimeUnit.SECONDS);
		CountDownLatch queued = new CountDownLatch(3);
		List<Future<?>> rest = new ArrayList<>();
		for (String name : List.of("lookup-1", "lookup-2")) {
			rest.add(callers.submit(() -> {
				queued.countDown();
				return lane.call(Priority.LOOKUP, 10_000, () -> order.add(name));
			}));
		}
		Thread.sleep(30);
		rest.add(callers.submit(() -> {
			queued.countDown();
			return lane.call(Priority.ORDER, 10_000, () -> order.add("ORDER"));
		}));
		for (Future<?> f : rest) {
			f.get(10, TimeUnit.SECONDS);
		}
		assertThat(order.get(0)).isEqualTo("first");
		assertThat(order.indexOf("ORDER")).isLessThan(order.indexOf("lookup-2"));
	}

	@Test
	void lookupThatWaitsTooLongIsDroppedAndNeverRuns() throws Exception {
		lane = new KisLane("t3", 400);
		List<String> ran = Collections.synchronizedList(new ArrayList<>());
		Future<?> a = callers.submit(() -> lane.call(Priority.LOOKUP, 10_000, () -> ran.add("a")));
		a.get(5, TimeUnit.SECONDS);
		// 다음 시작 시각이 400ms 뒤인데 기다릴 수 있는 시간은 50ms 뿐이다.
		assertThatThrownBy(() -> lane.call(Priority.LOOKUP, 50, () -> ran.add("late")))
			.isInstanceOf(KisClientException.class);
		Thread.sleep(600);
		assertThat(ran).containsExactly("a");
	}

	@Test
	void failureOfOneRequestDoesNotStopTheLane() {
		lane = new KisLane("t4", 20);
		assertThatThrownBy(() -> lane.call(Priority.LOOKUP, 5_000, () -> {
			throw new InvalidApiRequestException("{\"msg_cd\":\"EGW00201\"}", 500);
		})).isInstanceOf(InvalidApiRequestException.class);
		assertThat(lane.call(Priority.LOOKUP, 5_000, () -> "ok")).isEqualTo("ok");
	}

	@Test
	void classifiesFailures() {
		assertThat(KisFailure.classify(new InvalidApiRequestException("{\"msg_cd\":\"EGW00201\",\"msg1\":\"초당 거래건수를 초과\"}", 500)))
			.isEqualTo(KisFailure.RATE_LIMIT);
		assertThat(KisFailure.classify(new InvalidApiRequestException("{\"msg_cd\":\"EGW00123\"}", 401))).isEqualTo(KisFailure.TOKEN);
		assertThat(KisFailure.classify(new InvalidApiRequestException("server error", 503))).isEqualTo(KisFailure.SERVER);
		assertThat(KisFailure.classify(new InvalidApiRequestException("bad", 400))).isEqualTo(KisFailure.REJECTED);
		assertThat(KisFailure.classify(new InvalidApiRequestException("Failed to get response from server. (timed out)",
			new java.io.IOException("timed out")))).isEqualTo(KisFailure.NETWORK);
		assertThat(KisFailure.classify(new KisClientException("주문가능금액을 초과 했습니다"))).isEqualTo(KisFailure.REJECTED);
		assertThat(KisFailure.RATE_LIMIT.isAmbiguous()).isFalse();
		assertThat(KisFailure.NETWORK.isAmbiguous()).isTrue();
		assertThat(KisFailure.REJECTED.isTransient()).isFalse();
	}

	@Test
	void globalConstantsMatchTheKisLimits() {
		assertThat(KisLimits.REAL_PER_SECOND).isLessThanOrEqualTo(KisLimits.REAL_OFFICIAL_PER_SECOND);
		assertThat(KisLimits.PAPER_PER_SECOND).isLessThanOrEqualTo(KisLimits.PAPER_OFFICIAL_PER_SECOND);
		assertThat(KisLimits.spacingMillis(true)).isGreaterThanOrEqualTo(1000);
		assertThat(1000.0 / KisLimits.spacingMillis(false)).isLessThanOrEqualTo(KisLimits.REAL_OFFICIAL_PER_SECOND);
		assertThat(KisLimits.backoffSeconds(1)).isEqualTo(5);
		assertThat(KisLimits.backoffSeconds(99)).isEqualTo(300);
	}
}
