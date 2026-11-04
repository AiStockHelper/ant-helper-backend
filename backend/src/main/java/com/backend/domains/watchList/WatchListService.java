package com.backend.domains.watchList;

import java.util.List;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backend.common.exception.ApiException;
import com.backend.common.exception.ErrorCode;
import com.backend.domains.watchList.dto.response.FindWatchListResponse;
import com.backend.domains.watchList.entity.WatchList;
import com.backend.domains.watchList.enums.MarketType;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WatchListService {

	private final WatchListRepository watchListRepository;
	private static final int WATCH_LIST_MAX_COUNT = 50;

	// 관심 목록
	@Tool(name = "find_user_stock_watch_lists", description = "사용자가 '관심목록을 보여줘'라고 요청했을 때만 호출됩니다. 종목 추천 시에는 호출되지 않습니다.")
	public List<FindWatchListResponse> findWatchLists(
		@ToolParam(description = "멤버 ID")
		final Long memberId
	) {
		return watchListRepository.findAllByMemberIdWithStock(memberId);
	}

	// 관심 목록 추가
	@Transactional
	@Tool(name = "add_stock_watch_list", description = "member의 관심 목록에 새로운 종목을 추가합니다. 사용자가 관심목록 저장을 요청했을 때에만 호출되어야 합니다.")
	public void addWatchList(
		@ToolParam(description = "멤버 ID")
		final Long memberId,
		@ToolParam(description = "종목 번호")
		final String productNumber,
		@ToolParam(description = "시장 구분 코드")
		final MarketType marketType
	) {
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
	@Tool(name = "delete_user_stock_watch_list", description = "member의 관심 목록에서 종목을 삭제합니다. 사용자가 관심목록 삭제를 요청했을 때에만 호출되어야 합니다.")
	public void deleteWatchList(
		@ToolParam(description = "멤버 ID")
		final Long memberId,
		@ToolParam(description = "관심목록 ID")
		final Long watchListId
	) {
		// 해당 멤버의 관심목록이 아니면 삭제되지 않음
		watchListRepository.deleteByIdAndMemberId(watchListId, memberId);
	}
}
