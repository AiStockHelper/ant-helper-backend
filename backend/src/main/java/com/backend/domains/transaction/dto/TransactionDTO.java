package com.backend.domains.transaction.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import com.backend.domains.transaction.domain.TransactionItem;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

public class TransactionDTO {

	@Getter
	@NoArgsConstructor(access = AccessLevel.PRIVATE)
	public static class SaveTransactionRequest {

		@Min(value = 0, message = "금액은 0원 이상이어야 합니다.")
		private int amount;

		@Size(min = 1, max = 10, message = "주문은 최소 {min}개, 최대 {max}개까지 가능합니다.")
		private List<Element> transactionItems;

		@Getter
		@NoArgsConstructor(access = AccessLevel.PRIVATE)
		public static class Element {

			@NotBlank(message = "productNumber는 비어있을 수 없습니다.")
			private String productNumber;

			@NotBlank(message = "name은 비어있을 수 없습니다.")
			private String name;

			@NotBlank(message = "industry는 비어있을 수 없습니다.")
			private String industry;
		}
	}

	@Getter
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class FindTransactionResponse {

		private boolean existence;
		private Integer amount;
		private List<Element> transactionItems;

		public static FindTransactionResponse of(boolean existence, Integer amount, List<Element> items) {
			return new FindTransactionResponse(
				existence,
				amount,
				items);
		}

		@Getter
		@AllArgsConstructor(access = AccessLevel.PRIVATE)
		public static class Element {

			private Long id;

			private String productNumber;

			private String name;

			private String industry;

			public static Element from(TransactionItem transactionItem) {
				return new Element(
					transactionItem.getId(),
					transactionItem.getProductNumber(),
					transactionItem.getName(),
					transactionItem.getIndustry());
			}
		}
	}
}
