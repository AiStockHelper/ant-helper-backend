package com.backend.common.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@ConfigurationProperties(prefix = "kis")
public class KisProperties {

	private String url;
	private String orderTrId;
	private String sellTrId;
	private String getBalanceTrId;
	private String getBalanceRealizedProfitAndLossTrId;
	private String getPriceTrId;
	private String findDomesticStockPriceChartId;
}
