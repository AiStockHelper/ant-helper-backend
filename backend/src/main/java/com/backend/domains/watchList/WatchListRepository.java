package com.backend.domains.watchList;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.backend.domains.member.domain.Member;
import com.backend.domains.watchList.domain.WatchList;

public interface WatchListRepository extends JpaRepository<WatchList, Long> {

	Page<WatchList> findAllByMemberId(Long memberId, Pageable pageable);

	int countByMemberId(Long memberId);

	void deleteByIdAndMemberId(Long id, Long memberId);
}
