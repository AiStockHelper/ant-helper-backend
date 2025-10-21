package com.backend.domains.watchList;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backend.common.exception.ApiException;
import com.backend.common.exception.ErrorCode;
import com.backend.domains.member.domain.Member;
import com.backend.domains.watchList.domain.WatchList;
import com.backend.domains.watchList.dto.WatchListDTO.AddWatchListRequest;
import com.backend.domains.watchList.dto.WatchListDTO.FindWatchListResponse;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WatchListService {

	private final WatchListRepository watchListRepository;
	private static final int WATCH_LIST_MAX_COUNT = 50;

	// 관심 목록 조회
	public Page<FindWatchListResponse> findWatchLists(Member member, Pageable pageable) {
		Page<WatchList> watchLists = watchListRepository.findAllByMember(member, pageable);

		return watchLists.map(FindWatchListResponse::from);
	}

	// 관심 목록 추가
	@Transactional
	public void addWatchList(Member member, AddWatchListRequest request) {
		checkWatchListCount(member);

		WatchList watchList = request.toEntity(member);

		watchListRepository.save(watchList);
	}

	private void checkWatchListCount(Member member) {
		int watchListCount = watchListRepository.countByMember(member);

		if (watchListCount > WATCH_LIST_MAX_COUNT) {
			throw ApiException.from(ErrorCode.TOO_MANY_WATCH_LIST);
		}
	}

	// 관심 목록 삭제
	@Transactional
	public void deleteWatchList(Long watchListId) {
		watchListRepository.deleteById(watchListId);
	}
}
