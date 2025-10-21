package com.backend.domains.transaction.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(uniqueConstraints = {
	@UniqueConstraint(name = "NAME_ROOM_UNIQUE", columnNames = {"transaction_id", "product_number"})})
public class TransactionItem {

	@Id
	@Column(name = "transaction_item_id")
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String productNumber;

	@Column(nullable = false)
	private String name;

	@Column(nullable = false)
	private String industry;

	@ManyToOne
	@JoinColumn(name = "transaction_id")
	private Transaction transaction;

	@Builder
	private TransactionItem(String productNumber, String name, String industry, Transaction transaction) {
		this.productNumber = productNumber;
		this.name = name;
		this.industry = industry;
		setTransaction(transaction);
	}

	public void setTransaction(Transaction transaction) {
		this.transaction = transaction;
		transaction.getTransactionItems().add(this);
	}
}
