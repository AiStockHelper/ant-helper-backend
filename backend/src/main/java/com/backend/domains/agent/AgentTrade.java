package com.backend.domains.agent;

import java.math.BigDecimal;
import java.time.Instant;

import com.backend.market.Market;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 자동 실행이 낸 주문 기록(감사 및 일일 한도 계산용). */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "agent_trade", indexes = {
	@Index(name = "idx_agent_trade_ledger", columnList = "ledger_id, created_at"),
	@Index(name = "idx_agent_trade_member", columnList = "member_id")
})
public class AgentTrade {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "ledger_id", nullable = false)
	private Long ledgerId;

	@Column(name = "task_id", nullable = false)
	private Long taskId;

	@Column(name = "member_id", nullable = false)
	private Long memberId;

	@Column(name = "side", nullable = false, length = 4)
	private String side;

	@Enumerated(EnumType.STRING)
	@Column(name = "market", nullable = false, length = 10)
	private Market market;

	@Column(name = "symbol", nullable = false, length = 20)
	private String symbol;

	@Column(name = "name", length = 100)
	private String name;

	@Column(name = "quantity", nullable = false)
	private long quantity;

	@Column(name = "price", nullable = false, precision = 20, scale = 4)
	private BigDecimal price;

	@Column(name = "amount", nullable = false, precision = 20, scale = 4)
	private BigDecimal amount;

	@Column(name = "order_no", length = 40)
	private String orderNo;

	@Column(name = "reason", length = 300)
	private String reason;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	public AgentTrade(Long ledgerId, Long taskId, Long memberId, String side, Market market, String symbol, String name,
		long quantity, BigDecimal price, String orderNo, String reason) {
		this.ledgerId = ledgerId;
		this.taskId = taskId;
		this.memberId = memberId;
		this.side = side;
		this.market = market;
		this.symbol = symbol;
		this.name = name;
		this.quantity = quantity;
		this.price = price;
		this.amount = price.multiply(BigDecimal.valueOf(quantity));
		this.orderNo = orderNo;
		this.reason = reason == null ? null : reason.length() > 300 ? reason.substring(0, 300) : reason;
		this.createdAt = Instant.now();
	}
}
