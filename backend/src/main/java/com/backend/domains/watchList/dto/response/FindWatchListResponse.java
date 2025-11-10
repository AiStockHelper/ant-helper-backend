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
	private String name;

	public static FindWatchListResponse of(WatchList watchList, String name) {
		return new FindWatchListResponse(
			watchList.getId(),
			watchList.getProductNumber(),
			name
		);
	}
}