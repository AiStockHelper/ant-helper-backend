package com.backend.domains.watchList;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backend.common.dto.PageResponse;
import com.backend.common.exception.ApiException;
import com.backend.common.exception.ErrorCode;
import com.backend.domains.watchList.domain.WatchList;
import com.backend.domains.watchList.dto.response.FindWatchListResponse;
import com.backend.domains.watchList.enums.MarketType;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WatchListService {

	private final WatchListRepository watchListRepository;
	private static final int WATCH_LIST_MAX_COUNT = 50;

	// 관심 목록 조회
	public PageResponse<FindWatchListResponse> findWatchLists(final Long memberId, final int size, final int page) {
		Pageable pageable = PageRequest.of(page, size);
		Page<FindWatchListResponse> pageResponse = watchListRepository.findAllByMemberId(memberId, pageable)
			.map(FindWatchListResponse::from);

		return PageResponse.of(pageResponse);
	}

	// 관심 목록 추가
	@Transactional
	public void addWatchList(final Long memberId, final String productNumber, final MarketType marketType) {
		// 관심 목록 개수가 최대치를 넘지 않는지 검증
		validateWatchListCount(memberId);

		WatchList watchList = WatchList.builder()
			.memberId(memberId)
			.productNumber(productNumber)
			.marketType(marketType)
			.build();
		watchListRepository.save(watchList);
	}

	// 관심 목록 개수 검증
	private void validateWatchListCount(final Long memberId) {
		int watchListCount = watchListRepository.countByMemberId(memberId);

		if (watchListCount > WATCH_LIST_MAX_COUNT) {
			throw ApiException.from(ErrorCode.TOO_MANY_WATCH_LIST);
		}
	}

	// 관심 목록 삭제
	@Transactional
	public void deleteWatchList(final Long memberId, final Long watchListId) {
		// 해당 멤버의 관심목록이 아니면 삭제되지 않음
		watchListRepository.deleteByIdAndMemberId(watchListId, memberId);
	}
}
