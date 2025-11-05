package com.backend.domains.watchList;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.backend.domains.watchList.entity.WatchList;

public interface WatchListRepository extends JpaRepository<WatchList, Long> {

	List<WatchList> findAllByMemberId(Long memberId);

	int countByMemberId(Long memberId);

	void deleteByIdAndMemberId(Long id, Long memberId);
}
