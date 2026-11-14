package com.backend.market.queue;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface KisOrderRequestRepository extends JpaRepository<KisOrderRequest, Long> {

	Optional<KisOrderRequest> findByIdAndMemberId(Long id, Long memberId);

	List<KisOrderRequest> findTop100ByMemberIdOrderByIdDesc(Long memberId);

	List<KisOrderRequest> findTop100ByMemberIdAndAccountIdOrderByIdDesc(Long memberId, Long accountId);

	List<KisOrderRequest> findTop100ByMemberIdAndStatusInOrderByIdDesc(Long memberId, Collection<KisOrderRequest.Status> statuses);

	List<KisOrderRequest> findTop100ByMemberIdAndAccountIdAndStatusInOrderByIdDesc(Long memberId, Long accountId,
		Collection<KisOrderRequest.Status> statuses);

	List<KisOrderRequest> findTop20ByStatusAndNextAttemptAtLessThanEqualOrderByIdAsc(KisOrderRequest.Status status,
		LocalDateTime now);

	List<KisOrderRequest> findByStatusAndUpdatedAtBefore(KisOrderRequest.Status status, LocalDateTime before);

	long countByMemberIdAndStatus(Long memberId, KisOrderRequest.Status status);

	/** 스케줄러가 같은 행을 두 번 처리하지 않도록 RETRY_WAIT → SENDING 전환에 성공한 쪽만 진행한다. */
	@Modifying
	@Query("update KisOrderRequest r set r.status = com.backend.market.queue.KisOrderRequest.Status.SENDING, "
		+ "r.updatedAt = :now where r.id = :id and r.status = com.backend.market.queue.KisOrderRequest.Status.RETRY_WAIT")
	int claim(@Param("id") Long id, @Param("now") LocalDateTime now);

	void deleteAllByMemberId(Long memberId);

	void deleteAllByAccountId(Long accountId);
}
