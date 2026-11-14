package com.backend.domains.agent;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentRunRepository extends JpaRepository<AgentRun, Long> {

	List<AgentRun> findTop20ByTaskIdOrderByIdDesc(Long taskId);

	List<AgentRun> findTop5ByTaskIdAndStatusOrderByIdDesc(Long taskId, AgentRun.Status status);

	void deleteAllByMemberId(Long memberId);
}
