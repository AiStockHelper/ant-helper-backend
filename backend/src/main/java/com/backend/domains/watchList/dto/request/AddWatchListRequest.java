package com.backend.domains.watchList.dto.request;

import com.backend.domains.watchList.enums.MarketType;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class AddWatchListRequest {
	@NotBlank(message = "productNumber는 비어있을 수 없습니다.")
	@Schema(description = "상품 번호", example = "005930")
	private String productNumber;

	@NotNull(message = "marketType는 null일 수 없습니다.")
	@Schema(description = "시장 종류", example = "DOMESTIC_STOCK")
	private MarketType marketType;
}