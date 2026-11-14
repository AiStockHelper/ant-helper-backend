package com.backend.domains.agent;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 15초마다 실행 시각이 된 자동 실행을 찾아 각각 별도 스레드에서 실행한다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentRunner {

	private final AgentTaskRepository tasks;
	private final AgentRunService runService;

	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

	@Scheduled(fixedDelay = 15_000, initialDelay = 20_000)
	public void tick() {
		List<AgentTask> due = tasks.findByStatusAndNextRunAtLessThanEqualOrderByNextRunAtAsc(AgentTask.Status.ACTIVE,
			Instant.now());
		if (!due.isEmpty()) {
			log.info("실행 시각이 된 자동 실행 {}건을 시작합니다.", due.size());
		}
		for (AgentTask t : due) {
			Long id = t.getId();
			executor.submit(() -> {
				try {
					runService.execute(id, false);
				} catch (RuntimeException e) {
					log.error("자동 실행 {} 처리 중 예기치 않은 오류", id, e);
				}
			});
		}
	}

	@PreDestroy
	void shutdown() {
		executor.shutdown();
	}
}
