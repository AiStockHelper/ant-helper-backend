package com.backend.domains.agent;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.backend.market.Market;

public interface AgentPositionRepository extends JpaRepository<AgentPosition, Long> {

	List<AgentPosition> findByLedgerIdAndStatus(Long ledgerId, AgentPosition.Status status);

	List<AgentPosition> findTop60ByLedgerIdOrderByIdDesc(Long ledgerId);

	List<AgentPosition> findByLedgerId(Long ledgerId);

	Optional<AgentPosition> findFirstByLedgerIdAndMarketAndSymbolAndStatus(Long ledgerId, Market market, String symbol,
		AgentPosition.Status status);

	void deleteAllByMemberId(Long memberId);
}
