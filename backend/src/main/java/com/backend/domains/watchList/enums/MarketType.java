package com.backend.domains.watchList.enums;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "시장 종류")
public enum MarketType {
	@Schema(description = "국내 주식")
	DOMESTIC_STOCK,
	@Schema(description = "해외 주식")
	OVERSEAS_STOCK
}
