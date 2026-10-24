package com.backend.domains.schedule;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ScheduledTaskRepository extends JpaRepository<ScheduledTask, Long> {

	Optional<ScheduledTask> findByIdAndMemberId(Long id, Long memberId);

	List<ScheduledTask> findByMemberIdOrderByRunAtDesc(Long memberId);

	List<ScheduledTask> findByMemberIdAndAccountIdOrderByRunAtDesc(Long memberId, Long accountId);

	List<ScheduledTask> findByStatusAndRunAtLessThanEqualOrderByRunAtAsc(ScheduledTask.Status status, Instant now);

	long countByMemberIdAndStatus(Long memberId, ScheduledTask.Status status);

	@Modifying
	@Query("update ScheduledTask t set t.status = com.backend.domains.schedule.ScheduledTask.Status.RUNNING "
		+ "where t.id = :id and t.status = com.backend.domains.schedule.ScheduledTask.Status.SCHEDULED")
	int claim(@Param("id") Long id);

	@Modifying
	@Query("update ScheduledTask t set t.status = com.backend.domains.schedule.ScheduledTask.Status.CANCELED, "
		+ "t.resultMessage = :reason where t.accountId = :accountId "
		+ "and t.status = com.backend.domains.schedule.ScheduledTask.Status.SCHEDULED")
	void cancelScheduledForAccount(@Param("accountId") Long accountId, @Param("reason") String reason);

	void deleteAllByMemberId(Long memberId);
}
