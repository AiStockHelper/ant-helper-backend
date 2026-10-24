package com.backend.domains.chat.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.backend.domains.chat.entity.ChatAction;

public interface ChatActionRepository extends JpaRepository<ChatAction, Long> {

	Optional<ChatAction> findByIdAndMemberId(Long id, Long memberId);

	List<ChatAction> findByMessageIdIn(Collection<Long> messageIds);

	List<ChatAction> findByMessageId(Long messageId);

	/** 같은 상태 전이를 두 번 실행하지 않도록 PENDING 일 때만 선점한다. */
	@Modifying
	@Query("update ChatAction a set a.status = com.backend.domains.chat.entity.ChatAction.Status.APPROVED "
		+ "where a.id = :id and a.status = com.backend.domains.chat.entity.ChatAction.Status.PENDING")
	int claim(@Param("id") Long id);

	@Modifying
	@Query("delete from ChatAction a where a.conversationId = :conversationId")
	void deleteByConversation(@Param("conversationId") Long conversationId);

	@Modifying
	@Query("delete from ChatAction a where a.messageId in :messageIds")
	void deleteByMessageIds(@Param("messageIds") Collection<Long> messageIds);

	@Modifying
	@Query("delete from ChatAction a where a.memberId = :memberId")
	void deleteByMember(@Param("memberId") Long memberId);

	@Modifying
	@Query("update ChatAction a set a.status = com.backend.domains.chat.entity.ChatAction.Status.EXPIRED "
		+ "where a.accountId = :accountId and a.status = com.backend.domains.chat.entity.ChatAction.Status.PENDING")
	void expirePendingForAccount(@Param("accountId") Long accountId);
}
