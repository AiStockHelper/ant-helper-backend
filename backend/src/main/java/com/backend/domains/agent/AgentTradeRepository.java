package com.backend.domains.agent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AgentTradeRepository extends JpaRepository<AgentTrade, Long> {

	List<AgentTrade> findTop40ByLedgerIdOrderByIdDesc(Long ledgerId);

	/** 매도별 손익을 시간순으로 다시 계산하려고 장부의 모든 거래를 오래된 것부터 읽는다. */
	List<AgentTrade> findByLedgerIdOrderByIdAsc(Long ledgerId);

	@Query("select coalesce(sum(t.amount), 0) from AgentTrade t where t.ledgerId = :ledgerId and t.side = 'BUY' "
		+ "and t.createdAt >= :since")
	BigDecimal boughtSince(@Param("ledgerId") Long ledgerId, @Param("since") Instant since);

	/** 이 장부가 기록한 주문(앱이 낸 주문)의 (주문시각, 주문번호). 앱 밖 주문을 가려낼 때 쓴다. */
	@Query("select t from AgentTrade t where t.ledgerId = :ledgerId and t.orderNo is not null")
	List<AgentTrade> withOrderNo(@Param("ledgerId") Long ledgerId);

	void deleteAllByMemberId(Long memberId);
}
