package com.backend.domains.watchList;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.backend.common.dto.DataResponse;
import com.backend.common.dto.ErrorResponse;
import com.backend.common.dto.PageResponse;
import com.backend.domains.watchList.dto.request.AddWatchListRequest;
import com.backend.domains.watchList.dto.response.FindWatchListResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

@Tag(name = "WATCHLIST API", description = "관심목록에 대한 API입니다.")
@RestController
@RequestMapping("/api/watchlist")
@RequiredArgsConstructor
@Validated
public class WatchListController {

	private final WatchListService watchListService;

	@GetMapping
	@Operation(
		summary = "관심목록 조회",
		description = """
			관심목록을 페이지네이션으로 가져온다.
			
			size는 최소 1, 최대 10이다.
			page는 0부터 시작한다."""
	)
	public ResponseEntity<DataResponse<PageResponse<FindWatchListResponse>>> findWatchLists(
		@AuthenticationPrincipal Long memberId,
		@RequestParam("size") @Min(value = 1, message = "size는 1이상이어야 합니다.") @Max(value = 10, message = "size는 10이하이어야 합니다.") int size,
		@RequestParam("page") @Min(value = 0, message = "page는 0이상이어야 합니다.") int page
	) {
		PageResponse<FindWatchListResponse> response = watchListService.findWatchLists(memberId, size, page);

		return ResponseEntity.ok(DataResponse.from(response));
	}

	@PostMapping
	@Operation(
		summary = "관심목록 추가",
		description = "관심목록을 추가한다.",
		responses = {
			@ApiResponse(
				responseCode = "200",
				description = "성공"
			),
			@ApiResponse(
				responseCode = "401",
				description = "유효하지 않은 액세스 토큰입니다.",
				content = @Content(schema = @Schema(implementation = ErrorResponse.class))
			),
			@ApiResponse(
				responseCode = "429",
				description = "관심목록이 50개를 초과했습니다.",
				content = @Content(schema = @Schema(implementation = ErrorResponse.class))
			)
		}
	)
	public ResponseEntity<DataResponse<Void>> addWatchList(
		@AuthenticationPrincipal Long memberId,
		@RequestBody @Valid AddWatchListRequest request
	) {
		watchListService.addWatchList(memberId, request.getProductNumber(), request.getMarketType());

		return ResponseEntity.ok(DataResponse.ok());
	}

	@DeleteMapping("/{watchListId}")
	@Operation(
		summary = "관심목록 삭제",
		description = "관심목록을 삭제한다.",
		responses = {
			@ApiResponse(
				responseCode = "200",
				description = "성공"
			),
			@ApiResponse(
				responseCode = "401",
				description = "유효하지 않은 액세스 토큰입니다.",
				content = @Content(schema = @Schema(implementation = ErrorResponse.class))
			)
		}
	)
	public ResponseEntity<DataResponse<Void>> deleteWatchList(
		@AuthenticationPrincipal Long memberId,
		@PathVariable("watchListId") Long watchListId
	) {
		watchListService.deleteWatchList(memberId, watchListId);

		return ResponseEntity.ok(DataResponse.ok());
	}
}
