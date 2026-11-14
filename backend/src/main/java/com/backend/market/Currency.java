package com.backend.market;

import java.math.BigDecimal;
import java.math.RoundingMode;

public enum Currency {

	KRW(0, "원"),
	USD(2, "달러");

	private final int scale;
	private final String koreanName;

	Currency(int scale, String koreanName) {
		this.scale = scale;
		this.koreanName = koreanName;
	}

	public int scale() {
		return scale;
	}

	public String koreanName() {
		return koreanName;
	}

	public BigDecimal round(BigDecimal value) {
		return value.setScale(scale, RoundingMode.HALF_UP);
	}

	/** 사용자에게 보여줄 금액 표시. 원화와 달러가 헷갈리지 않도록 단위를 뒤에 붙인다: 71,200원 / 189.50달러 */
	public String format(BigDecimal value) {
		BigDecimal v = round(value);
		String number = String.format("%,." + scale + "f", v);
		return number + koreanName;
	}
}
