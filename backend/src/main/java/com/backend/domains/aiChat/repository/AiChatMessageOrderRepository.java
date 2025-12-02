package com.backend.domains.aiChat.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.backend.domains.aiChat.entity.AiChatMessageOrderEntity;

import jakarta.persistence.LockModeType;

public interface AiChatMessageOrderRepository extends JpaRepository<AiChatMessageOrderEntity, Long> {

	/** 레코드를 조회하고 FOR UPDATE로 락을 건다 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
		    SELECT c FROM AiChatMessageOrderEntity c
		    WHERE c.aiChatRoomId = :aiChatRoomId
		""")
	Optional<AiChatMessageOrderEntity> findByAiChatRoomIdForUpdate(
		@Param("aiChatRoomId") Long aiChatRoomId
	);
}
