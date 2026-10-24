package com.backend.domains.chat.repository;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.backend.domains.chat.entity.ChatMessage;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

	List<ChatMessage> findByConversationIdOrderByIdAsc(Long conversationId);

	/** 최신 N개를 id 내림차순으로 가져온다(호출 측에서 뒤집어 사용). */
	List<ChatMessage> findByConversationIdAndIdLessThanOrderByIdDesc(Long conversationId, Long id, Pageable pageable);

	@Modifying
	@Query("delete from ChatMessage m where m.conversationId = :conversationId and m.id >= :fromId")
	int deleteFrom(@Param("conversationId") Long conversationId, @Param("fromId") Long fromId);

	@Modifying
	@Query("delete from ChatMessage m where m.conversationId = :conversationId")
	void deleteByConversation(@Param("conversationId") Long conversationId);

	@Modifying
	@Query("update ChatMessage m set m.status = com.backend.domains.chat.entity.ChatMessage.Status.ERROR "
		+ "where m.status = com.backend.domains.chat.entity.ChatMessage.Status.STREAMING")
	int markInterruptedStreams();

	@Modifying
	@Query("delete from ChatMessage m where m.memberId = :memberId")
	void deleteByMember(@Param("memberId") Long memberId);
}
