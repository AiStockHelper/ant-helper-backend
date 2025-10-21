package com.backend.domains.watchList.dto;

import jakarta.validation.constraints.NotBlank;
import com.backend.domains.member.domain.Member;
import com.backend.domains.watchList.domain.WatchList;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

public class WatchListDTO {

	@Getter
	@NoArgsConstructor(access = AccessLevel.PRIVATE)
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	public static class FindWatchListResponse {

		private Long id;
		private String productNumber;
		private String name;
		private String industry;

		public static FindWatchListResponse from(WatchList watchList) {
			return new FindWatchListResponse(
				watchList.getId(),
				watchList.getProductNumber(),
				watchList.getName(),
				watchList.getIndustry());
		}
	}

	@Getter
	@NoArgsConstructor(access = AccessLevel.PRIVATE)
	public static class AddWatchListRequest {

		@NotBlank(message = "productNumber는 비어있을 수 없습니다.")
		private String productNumber;

		@NotBlank(message = "name은 비어있을 수 없습니다.")
		private String name;

		@NotBlank(message = "industry는 비어있을 수 없습니다.")
		private String industry;

		public WatchList toEntity(Member member) {
			return WatchList.builder()
				.productNumber(productNumber)
				.name(name)
				.industry(industry)
				.member(member)
				.build();
		}
	}
}
