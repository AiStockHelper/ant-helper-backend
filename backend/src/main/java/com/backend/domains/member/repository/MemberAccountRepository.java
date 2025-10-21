package com.backend.domains.member.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.backend.domains.member.domain.MemberAccount;

public interface MemberAccountRepository extends JpaRepository<MemberAccount, Long> {
	List<MemberAccount> findAllByMemberId(Long memberId);

	boolean existsByIdAndMemberId(Long memberAccountId, Long memberId);

	Optional<MemberAccount> findByIdAndMemberId(Long memberAccountId, Long memberId);

	boolean existsByAccountNumber(String accountNumber);
}
