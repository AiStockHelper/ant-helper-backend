package com.backend.domains.schedule;

import java.time.Instant;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 15초마다 실행 시각이 된 예약 작업을 찾아 실행한다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduledTaskRunner {

	private final ScheduledTaskRepository tasks;
	private final ScheduleService scheduleService;

	@Scheduled(fixedDelay = 15_000, initialDelay = 20_000)
	public void tick() {
		List<ScheduledTask> due = tasks.findByStatusAndRunAtLessThanEqualOrderByRunAtAsc(
			ScheduledTask.Status.SCHEDULED, Instant.now());
		if (!due.isEmpty()) {
			log.info("실행 시각이 된 예약 {}건을 처리합니다.", due.size());
		}
		for (ScheduledTask t : due) {
			try {
				scheduleService.execute(t.getId());
			} catch (RuntimeException e) {
				log.error("예약 작업 {} 처리 중 예기치 않은 오류", t.getId(), e);
			}
		}
	}
}
