package com.backend.domains.chat.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.backend.domains.chat.entity.ChatConversation;

public interface ChatConversationRepository extends JpaRepository<ChatConversation, Long> {

	Optional<ChatConversation> findByIdAndMemberId(Long id, Long memberId);

	@Query("""
		select c from ChatConversation c
		where c.memberId = :memberId
		order by c.pinned desc, c.updatedAt desc
		""")
	Page<ChatConversation> findAllForMember(@Param("memberId") Long memberId, Pageable pageable);

	@Query("""
		select c from ChatConversation c
		where c.memberId = :memberId
		  and (lower(c.title) like lower(concat('%', :q, '%'))
		       or exists (select 1 from ChatMessage m
		                  where m.conversationId = c.id
		                    and lower(m.content) like lower(concat('%', :q, '%'))))
		order by c.pinned desc, c.updatedAt desc
		""")
	Page<ChatConversation> search(@Param("memberId") Long memberId, @Param("q") String q, Pageable pageable);

	@Modifying
	@Query("update ChatConversation c set c.accountId = null where c.accountId = :accountId")
	void clearAccount(@Param("accountId") Long accountId);

	void deleteAllByMemberId(Long memberId);
}
