package com.backend.domains.watchList;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.backend.domains.watchList.dto.response.FindWatchListResponse;
import com.backend.domains.watchList.entity.WatchList;

public interface WatchListRepository extends JpaRepository<WatchList, Long> {

	int countByMemberId(Long memberId);

	void deleteByIdAndMemberId(Long id, Long memberId);

	@Query("""
		SELECT new com.backend.domains.watchList.dto.response.FindWatchListResponse(
		    w.id,
		    s.productNumber,
		    s.name
		)
		FROM WatchList w
		JOIN DomesticStock s ON w.productNumber = s.productNumber
		WHERE w.memberId = :memberId
		""")
	List<FindWatchListResponse> findAllByMemberIdWithStock(@Param("memberId") Long memberId);
}
