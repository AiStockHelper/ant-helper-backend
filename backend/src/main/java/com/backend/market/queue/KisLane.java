package com.backend.market.queue;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import com.backend.order.kis.kis_client.exception.KisClientException;

import lombok.extern.slf4j.Slf4j;

/**
 * 앱키(계좌) 하나의 KIS 호출 줄. 요청을 줄에 세우고, 정해진 간격으로 한 건씩 꺼내 실행한다.
 * 주문이 조회보다 먼저 나가고, 같은 종류끼리는 들어온 순서대로 나간다.
 * 실행 자체는 가상 스레드에서 동시에 진행하므로 응답이 느려도 초당 한도까지는 처리량이 줄지 않는다.
 * 조회는 기다릴 수 있는 시간이 지나면 버려지고, 호출한 쪽은 실패를 받는다.
 */
@Slf4j
public final class KisLane {

	public enum Priority {
		ORDER, LOOKUP
	}

	private static final class Job implements Comparable<Job> {
		final Priority priority;
		final long seq;
		final long deadlineNanos;
		final Callable<?> task;
		final CompletableFuture<Object> future = new CompletableFuture<>();
		volatile boolean cancelled;

		Job(Priority priority, long seq, long deadlineNanos, Callable<?> task) {
			this.priority = priority;
			this.seq = seq;
			this.deadlineNanos = deadlineNanos;
			this.task = task;
		}

		@Override
		public int compareTo(Job o) {
			int c = priority.compareTo(o.priority);
			return c != 0 ? c : Long.compare(seq, o.seq);
		}
	}

	private final String name;
	private final long spacingNanos;
	private final PriorityBlockingQueue<Job> queue = new PriorityBlockingQueue<>();
	private final AtomicLong seq = new AtomicLong();
	private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
	private final Thread dispatcher;
	private volatile boolean closed;

	public KisLane(String name, long spacingMillis) {
		this.name = name;
		this.spacingNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(1, spacingMillis));
		this.dispatcher = Thread.ofPlatform().daemon().name("kis-lane-" + name).start(this::dispatchLoop);
	}

	public int waiting() {
		return queue.size();
	}

	/** 줄에 세우고 결과가 나올 때까지 기다린다. 기다릴 수 있는 시간을 넘기면 요청을 버리고 KisClientException. */
	@SuppressWarnings("unchecked")
	public <T> T call(Priority priority, long maxWaitMillis, Callable<T> task) {
		if (closed) {
			throw new KisClientException("한국투자증권 요청 큐가 닫혀 있습니다.");
		}
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(maxWaitMillis);
		Job job = new Job(priority, seq.incrementAndGet(), deadline, task);
		queue.add(job);
		try {
			// 큐 대기 시간 + 실제 호출 시간(HTTP 타임아웃 여유)
			return (T)job.future.get(maxWaitMillis + 30_000L, TimeUnit.MILLISECONDS);
		} catch (TimeoutException e) {
			job.cancelled = true;
			throw new KisClientException("한국투자증권 요청이 너무 오래 걸려 취소했습니다. 잠시 후 다시 시도해 주세요.");
		} catch (InterruptedException e) {
			job.cancelled = true;
			Thread.currentThread().interrupt();
			throw new KisClientException("한국투자증권 요청이 중단되었습니다.");
		} catch (ExecutionException e) {
			Throwable c = e.getCause();
			if (c instanceof RuntimeException r) {
				throw r;
			}
			throw new KisClientException("한국투자증권 요청 처리 중 오류가 발생했습니다: " + c, c);
		}
	}

	private void dispatchLoop() {
		long nextSlot = System.nanoTime();
		while (!closed) {
			try {
				// 다음 시작 시각까지 먼저 기다린 뒤 가장 우선순위가 높은 요청을 꺼낸다(기다리는 동안 들어온 주문이 앞선다).
				long wait = nextSlot - System.nanoTime();
				if (wait > 0) {
					LockSupport.parkNanos(wait);
					if (Thread.interrupted()) {
						break;
					}
					continue;
				}
				Job job = queue.take();
				if (job.cancelled) {
					continue;
				}
				if (System.nanoTime() > job.deadlineNanos) {
					job.future.completeExceptionally(new KisClientException(
						"한국투자증권 요청 대기 시간이 초과되어 버렸습니다. (대기열이 밀려 있습니다)"));
					log.warn("KIS 요청 대기 초과로 폐기 lane={} priority={} 대기열={}", name, job.priority, queue.size());
					continue;
				}
				nextSlot = Math.max(System.nanoTime(), nextSlot) + spacingNanos;
				workers.execute(() -> run(job));
			} catch (InterruptedException e) {
				break;
			} catch (RuntimeException e) {
				log.error("KIS lane 처리 중 오류 lane={}", name, e);
			}
		}
	}

	private void run(Job job) {
		if (job.cancelled) {
			return;
		}
		try {
			job.future.complete(job.task.call());
		} catch (Throwable t) {
			job.future.completeExceptionally(t);
		}
	}

	public void close() {
		closed = true;
		dispatcher.interrupt();
		workers.shutdown();
		Job j;
		while ((j = queue.poll()) != null) {
			j.future.completeExceptionally(new KisClientException("한국투자증권 요청 큐가 닫혔습니다."));
		}
	}
}
