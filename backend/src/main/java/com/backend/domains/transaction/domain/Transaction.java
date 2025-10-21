package com.backend.domains.transaction.domain;

import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import com.backend.domains.member.domain.Member;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Transaction {

	@Id
	@Column(name = "transaction_id")
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private int amount;

	@OneToMany(mappedBy = "transaction", cascade = CascadeType.ALL)
	private List<TransactionItem> transactionItems = new ArrayList<>();

	@ManyToOne
	@JoinColumn(name = "member_id")
	private Member member;

	@Builder
	private Transaction(int amount, Member member) {
		this.amount = amount;
		this.member = member;
	}

	private void setMember(Member member) {
		this.member = member;
		member.getTransactions().add(this);
	}
}
