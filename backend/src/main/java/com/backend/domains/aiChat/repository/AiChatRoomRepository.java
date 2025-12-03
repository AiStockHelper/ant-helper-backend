package com.backend.domains.aiChat.repository;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.backend.domains.aiChat.entity.AiChatRoomEntity;

@Repository
public interface AiChatRoomRepository extends JpaRepository<AiChatRoomEntity, Long> {

	Optional<AiChatRoomEntity> findByMemberId(Long memberId);

	boolean existsByMemberId(Long memberId);

	// insertIgnore를 통해 AiChatRoomEntity 생성
	@Modifying
	@Query(value = "INSERT IGNORE INTO ai_chat_rooms (member_id, created_at) VALUES (:memberId, :createdAt)", nativeQuery = true)
	void insertIgnore(@Param("memberId") Long memberId, LocalDateTime createdAt);
}