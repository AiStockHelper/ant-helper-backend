package com.backend.domains.watchList.dto.response;

import com.backend.domains.watchList.entity.WatchList;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class FindWatchListResponse {

	private Long id;
	private String productNumber;

	public static FindWatchListResponse from(WatchList watchList) {
		return new FindWatchListResponse(
			watchList.getId(),
			watchList.getProductNumber()
		);
	}
}