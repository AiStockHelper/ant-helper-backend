package com.backend.domains.agent;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AgentTaskRepository extends JpaRepository<AgentTask, Long> {

	Optional<AgentTask> findByIdAndMemberId(Long id, Long memberId);

	List<AgentTask> findByMemberIdOrderByIdDesc(Long memberId);

	List<AgentTask> findByMemberIdAndAccountIdOrderByIdDesc(Long memberId, Long accountId);

	List<AgentTask> findByStatusAndNextRunAtLessThanEqualOrderByNextRunAtAsc(AgentTask.Status status, Instant now);

	List<AgentTask> findByStatus(AgentTask.Status status);

	List<AgentTask> findByLedgerId(Long ledgerId);

	long countByMemberIdAndStatusIn(Long memberId, Collection<AgentTask.Status> statuses);

	@Modifying
	@Query("update AgentTask t set t.status = com.backend.domains.agent.AgentTask.Status.RUNNING "
		+ "where t.id = :id and t.status = com.backend.domains.agent.AgentTask.Status.ACTIVE")
	int claim(@Param("id") Long id);

	@Modifying
	@Query("update AgentTask t set t.status = com.backend.domains.agent.AgentTask.Status.PAUSED, t.pausedReason = :reason "
		+ "where t.memberId = :memberId and t.status in (com.backend.domains.agent.AgentTask.Status.ACTIVE, "
		+ "com.backend.domains.agent.AgentTask.Status.RUNNING)")
	int pauseAllForMember(@Param("memberId") Long memberId, @Param("reason") String reason);

	@Modifying
	@Query("update AgentTask t set t.status = com.backend.domains.agent.AgentTask.Status.CANCELED, t.pausedReason = :reason, "
		+ "t.nextRunAt = null where t.accountId = :accountId and t.status in "
		+ "(com.backend.domains.agent.AgentTask.Status.ACTIVE, com.backend.domains.agent.AgentTask.Status.RUNNING, "
		+ "com.backend.domains.agent.AgentTask.Status.PAUSED)")
	int cancelForAccount(@Param("accountId") Long accountId, @Param("reason") String reason);

	void deleteAllByMemberId(Long memberId);
}
