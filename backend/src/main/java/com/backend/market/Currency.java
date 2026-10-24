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

	/** 사람이 읽는 금액 표기: ₩71,200 / $189.50 */
	public String format(BigDecimal value) {
		BigDecimal v = round(value);
		String number = String.format("%,." + scale + "f", v);
		return (this == KRW ? "₩" : "$") + number;
	}
}
