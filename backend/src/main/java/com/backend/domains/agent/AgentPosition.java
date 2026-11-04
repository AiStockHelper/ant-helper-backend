package com.backend.domains.agent;

import java.math.BigDecimal;
import java.math.RoundingMode;
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

/**
 * 자동 실행이 직접 매수해서 관리하는 종목. 계좌의 다른 보유분과 구분하기 위한 장부(ledger)다.
 * 자율 주문은 이 장부에 있는 종목만 매도할 수 있다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "agent_position", indexes = {
	@Index(name = "idx_agent_pos_ledger", columnList = "ledger_id, status"),
	@Index(name = "idx_agent_pos_member", columnList = "member_id")
})
public class AgentPosition {

	public enum Status {OPEN, CLOSED, RELEASED}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "ledger_id", nullable = false)
	private Long ledgerId;

	@Column(name = "member_id", nullable = false)
	private Long memberId;

	@Column(name = "account_id", nullable = false)
	private Long accountId;

	@Enumerated(EnumType.STRING)
	@Column(name = "market", nullable = false, length = 10)
	private Market market;

	@Column(name = "symbol", nullable = false, length = 20)
	private String symbol;

	@Column(name = "name", length = 100)
	private String name;

	@Column(name = "quantity", nullable = false)
	private long quantity;

	@Column(name = "avg_price", nullable = false, precision = 20, scale = 4)
	private BigDecimal avgPrice;

	@Column(name = "last_price", precision = 20, scale = 4)
	private BigDecimal lastPrice;

	@Column(name = "realized_pnl", nullable = false, precision = 20, scale = 4)
	private BigDecimal realizedPnl = BigDecimal.ZERO;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 10)
	private Status status;

	@Column(name = "opened_at", nullable = false)
	private Instant openedAt;

	@Column(name = "closed_at")
	private Instant closedAt;

	@Column(name = "exit_reason", length = 200)
	private String exitReason;

	public AgentPosition(Long ledgerId, Long memberId, Long accountId, Market market, String symbol, String name,
		long quantity, BigDecimal price) {
		this.ledgerId = ledgerId;
		this.memberId = memberId;
		this.accountId = accountId;
		this.market = market;
		this.symbol = symbol;
		this.name = name;
		this.quantity = quantity;
		this.avgPrice = price;
		this.lastPrice = price;
		this.status = Status.OPEN;
		this.openedAt = Instant.now();
	}

	public BigDecimal cost() {
		return avgPrice.multiply(BigDecimal.valueOf(quantity));
	}

	public BigDecimal marketValue() {
		BigDecimal p = lastPrice == null ? avgPrice : lastPrice;
		return p.multiply(BigDecimal.valueOf(quantity));
	}

	public BigDecimal unrealizedPnl() {
		return status == Status.OPEN ? marketValue().subtract(cost()) : BigDecimal.ZERO;
	}

	public double pnlPct() {
		if (avgPrice.signum() == 0) {
			return 0;
		}
		BigDecimal p = lastPrice == null ? avgPrice : lastPrice;
		return p.subtract(avgPrice).divide(avgPrice, 6, RoundingMode.HALF_UP).doubleValue() * 100;
	}

	public void addBuy(long qty, BigDecimal price) {
		BigDecimal total = cost().add(price.multiply(BigDecimal.valueOf(qty)));
		this.quantity += qty;
		this.avgPrice = total.divide(BigDecimal.valueOf(this.quantity), 4, RoundingMode.HALF_UP);
		this.lastPrice = price;
	}

	public void applySell(long qty, BigDecimal price, String reason) {
		long sold = Math.min(qty, quantity);
		this.realizedPnl = realizedPnl.add(price.subtract(avgPrice).multiply(BigDecimal.valueOf(sold)));
		this.quantity -= sold;
		this.lastPrice = price;
		if (this.quantity <= 0) {
			this.status = Status.CLOSED;
			this.closedAt = Instant.now();
			this.exitReason = reason;
		}
	}

	public void shrinkTo(long qty, String reason) {
		this.quantity = qty;
		if (qty <= 0) {
			this.status = Status.RELEASED;
			this.closedAt = Instant.now();
			this.exitReason = reason;
		}
	}

	public void release(String reason) {
		this.status = Status.RELEASED;
		this.closedAt = Instant.now();
		this.exitReason = reason;
	}

	public void mark(BigDecimal price) {
		this.lastPrice = price;
	}

	public long heldDays(java.time.ZoneId zone) {
		return java.time.temporal.ChronoUnit.DAYS.between(openedAt.atZone(zone).toLocalDate(),
			Instant.now().atZone(zone).toLocalDate());
	}
}
