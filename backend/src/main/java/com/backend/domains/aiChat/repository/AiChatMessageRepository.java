package com.backend.domains.aiChat.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.backend.domains.aiChat.entity.AiChatMessageEntity;
import com.backend.domains.aiChat.enums.SenderType;

/**
 * 채팅 메시지 리포지토리
 */
public interface AiChatMessageRepository extends JpaRepository<AiChatMessageEntity, Long> {

	/**
	 * 특정 사용자-포스트의 메시지 목록 조회 (페이지네이션, 오래된 순)
	 */
	@Query("SELECT cm FROM AiChatMessageEntity cm " +
		"WHERE cm.memberId = :memberId " +
		"ORDER BY cm.messageOrder ASC")
	Page<AiChatMessageEntity> findByMemberIdOrderByMessageOrderAsc(
		@Param("memberId") Long memberId,
		Pageable pageable
	);

	/**
	 * AI 컨텍스트용 최근 N개 메시지 조회
	 */
	@Query("SELECT cm FROM AiChatMessageEntity cm " +
		"WHERE cm.memberId = :memberId " +
		"ORDER BY cm.messageOrder DESC")
	List<AiChatMessageEntity> findTopNByMemberIdOrderByMessageOrderDesc(
		@Param("memberId") Long memberId,
		Pageable pageable
	);

	/**
	 * 컨텍스트 초기화 시점 이후의 메시지들만 조회
	 */
	@Query("SELECT cm FROM AiChatMessageEntity cm " +
		"WHERE cm.memberId = :memberId " +
		"AND cm.createdAt > :contextResetAt " +
		"ORDER BY cm.messageOrder DESC")
	List<AiChatMessageEntity> findByMemberIdAndPostIdAfterContextReset(
		@Param("memberId") Long memberId,
		@Param("contextResetAt") LocalDateTime contextResetAt,
		Pageable pageable
	);

	Optional<AiChatMessageEntity> findByRequestIdAndSenderType(String requestId, SenderType senderType);

	Optional<AiChatMessageEntity> findByIdAndMemberId(Long id, Long memberId);

	int countByMemberId(Long memberId);
}