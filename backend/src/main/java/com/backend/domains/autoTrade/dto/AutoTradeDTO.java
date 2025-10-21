package com.backend.domains.autoTrade.dto;

import lombok.Getter;

public class AutoTradeDTO {

	@Getter
	public static class QuantitiesDTO {
		private final int quantity;

		public QuantitiesDTO(int quantity) {
			this.quantity = quantity;
		}
	}
}
