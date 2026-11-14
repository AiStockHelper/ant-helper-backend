package com.backend.domains.member.repository;

import java.util.List;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import com.backend.domains.member.domain.RefreshToken;

@Repository
public interface RefreshTokenRepository extends CrudRepository<RefreshToken, String> {

	List<RefreshToken> findAllByMemberId(Long memberId);

	/** Redis 저장소는 파생 delete 쿼리를 지원하지 않으므로 조회 후 지운다. */
	default void deleteByMemberId(Long memberId) {
		deleteAll(findAllByMemberId(memberId));
	}
}
