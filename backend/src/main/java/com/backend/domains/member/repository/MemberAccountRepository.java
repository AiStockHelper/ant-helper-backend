package com.backend.domains.member.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.backend.domains.member.domain.MemberAccount;
import com.backend.order.kis.enums.AccountType;

public interface MemberAccountRepository extends JpaRepository<MemberAccount, Long> {

	List<MemberAccount> findAllByMemberIdOrderByIdAsc(Long memberId);

	List<MemberAccount> findAllByMemberId(Long memberId);

	boolean existsByIdAndMemberId(Long memberAccountId, Long memberId);

	Optional<MemberAccount> findByIdAndMemberId(Long memberAccountId, Long memberId);

	boolean existsByAccountNumber(String accountNumber);

	boolean existsByMemberIdAndAccountNumberAndAccountType(Long memberId, String accountNumber, AccountType accountType);

	Optional<MemberAccount> findFirstByMemberIdAndIsDefaultTrue(Long memberId);

	void deleteAllByMemberId(Long memberId);
}
