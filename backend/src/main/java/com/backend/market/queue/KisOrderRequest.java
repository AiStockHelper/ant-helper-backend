package com.backend.market.queue;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.backend.domains.member.domain.MemberAccount;
import com.backend.domains.trade.TradePlanner.Plan;
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
 * 주문 원장. 매수·매도 요청은 KIS 로 보내기 전에 여기에 먼저 적재되고, 성공 여부와 재시도 횟수가 함께 남는다.
 * 일시적인 오류로 실패한 주문은 스케줄러가 RETRY_WAIT 인 행을 찾아 다시 보내고, 재시도를 모두 쓰면 FAILED 로 남아
 * 사용자가 '주문 실패 목록'에서 확인하고 지울 수 있다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "kis_order_request", indexes = {
	@Index(name = "idx_kor_member", columnList = "member_id, id"),
	@Index(name = "idx_kor_status", columnList = "status, next_attempt_at")
})
public class KisOrderRequest {

	private static final java.time.ZoneId SEOUL = java.time.ZoneId.of("Asia/Seoul");

	public enum Status {
		/** KIS 로 보내는 중 */
		SENDING,
		/** 일시적인 오류로 실패해서 다음 시도를 기다리는 중 */
		RETRY_WAIT,
		/** KIS 에 접수됨 */
		SUCCESS,
		/** 접수되지 못했고 더 시도하지 않음(주문 실패 목록에 표시) */
		FAILED
	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "member_id", nullable = false)
	private Long memberId;

	@Column(name = "account_id", nullable = false)
	private Long accountId;

	@Enumerated(EnumType.STRING)
	@Column(name = "source_type", nullable = false, length = 20)
	private OrderSource.Type sourceType;

	@Column(name = "source_ref")
	private Long sourceRef;

	@Column(name = "conversation_id")
	private Long conversationId;

	@Enumerated(EnumType.STRING)
	@Column(name = "market", nullable = false, length = 10)
	private Market market;

	@Column(name = "symbol", nullable = false, length = 20)
	private String symbol;

	@Column(name = "name", length = 100)
	private String name;

	@Column(name = "side", nullable = false, length = 4)
	private String side;

	@Column(name = "quantity", nullable = false)
	private long quantity;

	@Column(name = "limit_price", precision = 20, scale = 4)
	private BigDecimal limitPrice;

	@Column(name = "ref_price", precision = 20, scale = 4)
	private BigDecimal refPrice;

	@Column(name = "order_type_label", length = 120)
	private String orderTypeLabel;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 12)
	private Status status;

	/** 지금까지 KIS 로 보낸 횟수(첫 시도 포함) */
	@Column(name = "attempts", nullable = false)
	private int attempts;

	@Column(name = "next_attempt_at")
	private LocalDateTime nextAttemptAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "failure_kind", length = 12)
	private KisFailure failureKind;

	@Column(name = "last_error", length = 500)
	private String lastError;

	@Column(name = "order_no", length = 40)
	private String orderNo;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;

	@Column(name = "completed_at")
	private LocalDateTime completedAt;

	public static KisOrderRequest create(MemberAccount account, Plan plan, OrderSource source) {
		KisOrderRequest r = new KisOrderRequest();
		r.memberId = account.getMemberId();
		r.accountId = account.getId();
		r.sourceType = source.type();
		r.sourceRef = source.ref();
		r.conversationId = source.conversationId();
		r.market = plan.market();
		r.symbol = plan.symbol();
		r.name = plan.name();
		r.side = plan.side();
		r.quantity = plan.quantity();
		r.limitPrice = plan.limitPrice();
		r.refPrice = plan.refPrice();
		r.orderTypeLabel = plan.orderTypeLabel();
		r.status = Status.SENDING;
		r.attempts = 0;
		r.createdAt = LocalDateTime.now(SEOUL);
		r.updatedAt = r.createdAt;
		return r;
	}

	public Plan toPlan() {
		return new Plan(market, symbol, name, side, quantity, limitPrice, refPrice == null ? limitPrice : refPrice,
			orderTypeLabel == null ? "" : orderTypeLabel);
	}

	public void beginAttempt() {
		this.status = Status.SENDING;
		this.attempts++;
		this.updatedAt = LocalDateTime.now(SEOUL);
	}

	public void markSuccess(String orderNo) {
		this.status = Status.SUCCESS;
		this.orderNo = orderNo;
		this.nextAttemptAt = null;
		this.lastError = null;
		this.failureKind = null;
		this.completedAt = LocalDateTime.now(SEOUL);
		this.updatedAt = this.completedAt;
	}

	public void markRetry(LocalDateTime next, KisFailure kind, String error) {
		this.status = Status.RETRY_WAIT;
		this.nextAttemptAt = next;
		this.failureKind = kind;
		this.lastError = truncate(error);
		this.updatedAt = LocalDateTime.now(SEOUL);
	}

	public void markFailed(KisFailure kind, String error) {
		this.status = Status.FAILED;
		this.nextAttemptAt = null;
		this.failureKind = kind;
		this.lastError = truncate(error);
		this.completedAt = LocalDateTime.now(SEOUL);
		this.updatedAt = this.completedAt;
	}

	private static String truncate(String s) {
		return s == null ? null : (s.length() > 500 ? s.substring(0, 500) : s);
	}

	public boolean isFinished() {
		return status == Status.SUCCESS || status == Status.FAILED;
	}
}
