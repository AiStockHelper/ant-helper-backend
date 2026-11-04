package com.backend.domains.agent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AgentTradeRepository extends JpaRepository<AgentTrade, Long> {

	List<AgentTrade> findTop40ByLedgerIdOrderByIdDesc(Long ledgerId);

	@Query("select coalesce(sum(t.amount), 0) from AgentTrade t where t.ledgerId = :ledgerId and t.side = 'BUY' "
		+ "and t.createdAt >= :since")
	BigDecimal boughtSince(@Param("ledgerId") Long ledgerId, @Param("since") Instant since);

	void deleteAllByMemberId(Long memberId);
}
