package com.backend.market.queue;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.backend.common.exception.ApiException;
import com.backend.domains.chat.entity.ChatConversation;
import com.backend.domains.chat.entity.ChatMessage;
import com.backend.domains.chat.repository.ChatConversationRepository;
import com.backend.domains.chat.repository.ChatMessageRepository;
import com.backend.domains.email.EmailUtil;
import com.backend.domains.member.domain.MemberAccount;
import com.backend.domains.member.repository.MemberAccountRepository;
import com.backend.domains.member.repository.MemberRepository;
import com.backend.market.queue.OrderQueueService.Attempt;
import com.backend.market.queue.OrderQueueService.State;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 실패한 주문을 찾아 다시 보낸다. 몇 초마다 RETRY_WAIT 중 시간이 된 행을 가져와 한 건씩 순서대로 처리한다.
 * 서버가 전송 도중 멈춰 SENDING 으로 남은 행도 불확실한 실패로 보고 같은 절차(주문 내역 확인 → 재전송)를 거친다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderRetryScheduler {

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	private final KisOrderRequestRepository requests;
	private final MemberAccountRepository accounts;
	private final MemberRepository members;
	private final ChatConversationRepository conversations;
	private final ChatMessageRepository messages;
	private final OrderQueueService queue;
	private final EmailUtil mail;
	private final TransactionTemplate tx;

	@Scheduled(fixedDelay = 5_000, initialDelay = 20_000)
	public void tick() {
		try {
			recoverStuck();
			for (KisOrderRequest due : requests.findTop20ByStatusAndNextAttemptAtLessThanEqualOrderByIdAsc(
				KisOrderRequest.Status.RETRY_WAIT, LocalDateTime.now(SEOUL))) {
				Integer claimed = tx.execute(s -> requests.claim(due.getId(), LocalDateTime.now(SEOUL)));
				if (claimed == null || claimed != 1) {
					continue;
				}
				process(due.getId());
			}
		} catch (RuntimeException e) {
			log.error("주문 재시도 스케줄러 오류: {}", e.toString(), e);
		}
	}

	private void recoverStuck() {
		LocalDateTime limit = LocalDateTime.now(SEOUL).minusSeconds(KisLimits.STUCK_SENDING_SECONDS);
		for (KisOrderRequest r : requests.findByStatusAndUpdatedAtBefore(KisOrderRequest.Status.SENDING, limit)) {
			tx.executeWithoutResult(s -> requests.findById(r.getId()).ifPresent(x -> {
				if (x.getStatus() == KisOrderRequest.Status.SENDING) {
					x.markRetry(LocalDateTime.now(SEOUL), KisFailure.NETWORK, "전송 중 서버가 멈춰 처리 여부를 확인해야 합니다.");
					log.warn("전송 중에 멈춘 주문을 재처리 대상으로 돌립니다 request={}", x.getId());
				}
			}));
		}
	}

	private void process(Long id) {
		KisOrderRequest row = requests.findById(id).orElse(null);
		if (row == null) {
			return;
		}
		MemberAccount account = accounts.findById(row.getAccountId()).orElse(null);
		if (account == null) {
			queue.markCancelledOrMissing(id, null, row, "계좌가 삭제되어 주문을 취소했습니다.");
			return;
		}
		try {
			if (row.getFailureKind() != null && row.getFailureKind().isAmbiguous()) {
				Optional<String> existing = queue.findAlreadyPlaced(account, row);
				if (existing.isPresent()) {
					queue.markFoundOrder(id, existing.get());
					log.info("이전 시도가 실제로 접수되어 있었습니다 request={} orderNo={}", id, existing.get());
					notify(row, account, "대기 중이던 주문이 증권사에 접수된 것을 확인했습니다 (주문번호 " + existing.get() + ").");
					return;
				}
			}
			Attempt a = queue.attempt(id, account);
			if (a.state() == State.SUCCESS) {
				notify(row, account, "다시 시도해서 주문이 접수되었습니다 (주문번호 " + a.outcome().orderNo() + ").");
			} else if (a.state() == State.FAILED) {
				notifyFailure(row, account, a.outcome().message());
			}
		} catch (ApiException e) {
			notifyFailure(row, account, e.getMessage());
		} catch (RuntimeException e) {
			// 확인 조회 등이 실패한 경우: 이번 시도는 소모하지 않고 잠시 뒤 다시 처리한다.
			log.warn("주문 재처리를 미룹니다 request={}: {}", id, e.toString());
			tx.executeWithoutResult(s -> requests.findById(id).ifPresent(r -> r.markRetry(
				LocalDateTime.now(SEOUL).plusSeconds(KisLimits.backoffSeconds(Math.max(1, r.getAttempts()))),
				r.getFailureKind() == null ? KisFailure.NETWORK : r.getFailureKind(), "재처리 준비 중 오류: " + e.getMessage())));
		}
	}

	// ─────────────────────────── 알림 ───────────────────────────

	private void notify(KisOrderRequest row, MemberAccount account, String text) {
		String body = "[주문 큐] " + describe(row) + "\n\n" + text;
		postToConversation(row, body);
	}

	private void notifyFailure(KisOrderRequest row, MemberAccount account, String reason) {
		String body = "[주문 큐] " + describe(row) + "\n\n최대 " + KisLimits.ORDER_MAX_RETRIES
			+ "번까지 다시 시도했지만 주문을 접수하지 못했습니다. (" + reason + ")\n'주문 큐 > 실패한 주문'에서 확인하고 지울 수 있습니다.";
		postToConversation(row, body);
		try {
			members.findById(row.getMemberId()).ifPresent(m -> mail.sendEmail(m.getEmail(), "[Ant Helper] 주문을 접수하지 못했습니다",
				describe(row) + "\n계좌: " + account.displayName() + (account.isPaper() ? " (모의)" : " (실전)") + "\n사유: " + reason
					+ "\n\n앱의 '주문 큐 > 실패한 주문'에서 확인하세요."));
		} catch (RuntimeException e) {
			log.warn("주문 실패 알림 메일 실패 request={}: {}", row.getId(), e.getMessage());
		}
	}

	private static String describe(KisOrderRequest r) {
		return (r.getName() == null ? r.getSymbol() : r.getName()) + " " + r.getQuantity() + "주 "
			+ ("SELL".equalsIgnoreCase(r.getSide()) ? "매도" : "매수");
	}

	private void postToConversation(KisOrderRequest row, String text) {
		if (row.getConversationId() == null) {
			return;
		}
		try {
			tx.executeWithoutResult(s -> {
				ChatConversation conv = conversations.findById(row.getConversationId()).orElse(null);
				if (conv == null) {
					return;
				}
				ChatMessage m = ChatMessage.assistantPlaceholder(conv.getId(), row.getMemberId());
				m.finish(text, ChatMessage.Status.COMPLETE, null, "[]", "order-queue");
				messages.save(m);
				conv.updatePreview(text);
				conv.touch();
			});
		} catch (RuntimeException e) {
			log.warn("주문 결과를 대화에 남기지 못했습니다 request={}: {}", row.getId(), e.getMessage());
		}
	}
}
