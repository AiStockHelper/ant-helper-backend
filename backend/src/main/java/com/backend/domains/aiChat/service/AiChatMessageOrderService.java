package com.backend.domains.aiChat.service;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backend.common.exception.ApiException;
import com.backend.common.exception.ErrorCode;
import com.backend.domains.aiChat.entity.AiChatMessageOrderEntity;
import com.backend.domains.aiChat.repository.AiChatMessageOrderRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AiChatMessageOrderService {

	private final AiChatMessageOrderRepository aiChatMessageOrderRepository;

	/**
	 * (memberId, postId) 기준으로 다음 messageOrder 값을 원자적으로 가져옵니다.
	 */
	@Transactional
	public Long nextMessageOrder(final Long aiChatRoomId) {
		AiChatMessageOrderEntity seq = aiChatMessageOrderRepository.findByAiChatRoomIdForUpdate(aiChatRoomId)
			.orElseGet(() -> {
				try {
					return aiChatMessageOrderRepository.save(
						new AiChatMessageOrderEntity(aiChatRoomId, 0L)
					);
				} catch (DataIntegrityViolationException e) {
					// 만약 동시에 다른 쪽에서 memberId에 해당하는 레코드를 생성한 경우, 다시 조회
					return aiChatMessageOrderRepository.findByAiChatRoomIdForUpdate(aiChatRoomId)
						.orElseThrow(() -> new ApiException(ErrorCode.AI_CHAT_MESSAGE_ORDER_NOT_FOUND));
				}
			});
		seq.increment();
		return seq.getLastOrder();
	}
}
