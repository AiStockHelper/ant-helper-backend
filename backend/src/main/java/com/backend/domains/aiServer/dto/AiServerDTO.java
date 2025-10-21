package com.backend.domains.aiServer.dto;

import java.util.List;

import com.backend.domains.transaction.domain.Transaction;
import com.backend.domains.transaction.domain.TransactionItem;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

public class AiServerDTO {

	@Getter
	@NoArgsConstructor(access = AccessLevel.PRIVATE)
	@AllArgsConstructor
	public static class GetOrderListRequest {

		private int amount;

		private List<Element> stocks;

		public static GetOrderListRequest from(Transaction transaction, int amount) {
			return new GetOrderListRequest(
				amount,
				transaction.getTransactionItems().stream()
					.map(Element::from)
					.toList());
		}

		@Getter
		@NoArgsConstructor(access = AccessLevel.PRIVATE)
		@AllArgsConstructor
		public static class Element {
			private String productNumber;
			private String name;
			private String industry;

			public static Element from(TransactionItem transactionItem) {
				return new Element(
					transactionItem.getProductNumber(),
					transactionItem.getName(),
					transactionItem.getIndustry());
			}
		}
	}

	@Getter
	@NoArgsConstructor(access = AccessLevel.PRIVATE)
	public static class GetOrderListResponse {

		private List<Element> stocks;

		@Getter
		@NoArgsConstructor(access = AccessLevel.PRIVATE)
		@AllArgsConstructor
		public static class Element {
			private String productNumber;
			private String name;
			private int quantity;
		}
	}
}
