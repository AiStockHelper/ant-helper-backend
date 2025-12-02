package com.backend.domains.aiChat.service;

import static com.backend.common.exception.ErrorCode.*;
import static com.backend.domains.aiChat.enums.AiChatStatus.*;
import static com.backend.domains.aiChat.enums.SenderType.*;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backend.common.dto.PageResponse;
import com.backend.common.exception.ApiException;
import com.backend.common.exception.ErrorCode;
import com.backend.common.util.keyGenerator.KeyGenerator;
import com.backend.domains.aiChat.dto.request.ChatMessageRequest;
import com.backend.domains.aiChat.dto.response.ChatMessageResponse;
import com.backend.domains.aiChat.entity.AiChatMessageEntity;
import com.backend.domains.aiChat.entity.AiChatRoomEntity;
import com.backend.domains.aiChat.repository.AiChatMessageRepository;
import com.backend.domains.aiChat.repository.AiChatRoomRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 채팅 메시지 서비스
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AiChatMessageService {

	// aiChatMessage 관련
	private final AiChatMessageRepository aiChatMessageRepository;
	private final AiChatMessageOrderService aiChatMessageOrderService;

	// aiChatRoom 관련
	private final AiChatRoomService aiChatRoomService;
	private final AiChatRoomRepository aiChatRoomRepository;

	// aiServer 관련
	private final AiServerService aiServerService;

	// 기타
	private final KeyGenerator keyGenerator;

	// 사용자의 메시지,이미지 저장 후 AI 요청
	// @Transactional 붙이면 장애남(AiChatMessage가 커밋되기 전에 processAiRequest가 실행되어 MQ에서 메시지를 못찾음)
	public ChatMessageResponse sendUserMessage(final long memberId, final long memberAccountId, ChatMessageRequest request) {
		// 채팅방 조회
		AiChatRoomEntity aiChatRoom = aiChatRoomRepository.findByMemberId(memberId)
			.orElseThrow(() -> new ApiException(ErrorCode.AI_CHAT_ROOM_NOT_FOUND));

		// 다음 메시지 순서 조회
		Long messageOrder = aiChatMessageOrderService.nextMessageOrder(aiChatRoom.getId());

		// 요청 ID 생성
		String requestId = keyGenerator.generateKey();

		// 유저 요청 메시지 저장
		AiChatMessageEntity message = AiChatMessageEntity.builder()
			.memberId(memberId)
			.aiChatRoomId(aiChatRoom.getId())
			.messageOrder(messageOrder)
			.senderType(USER)
			.textContent(request.textContent())
			.status(REQUEST_PENDING)
			.requestId(requestId)
			.build();
		aiChatMessageRepository.save(message);

		// Ai 요청(비동기 처리)
		aiServerService.processAiRequest(memberId, memberAccountId, message.getId(), request.textContent());

		return ChatMessageResponse.createResponse(message);
	}

	// AI 채팅 요청 취소
	@Transactional
	public ChatMessageResponse cancelAiRequest(Long memberId, Long messageId) {
		// 유저ID와 메시지ID로 메시지 조회
		AiChatMessageEntity message = aiChatMessageRepository.findByIdAndMemberId(messageId, memberId)
			.orElseThrow(() -> new ApiException(AI_CHAT_MESSAGE_NOT_FOUND));

		// 요청이 아닌 것은 취소 불가
		if (!(message.getStatus() == REQUEST_PENDING)) {
			throw new ApiException(AI_CHAT_CANNOT_CANCEL);
		}

		// MQ에 있는 메세지는 삭제 불가능(리스너에서 후처리)

		// 요청메세지 상태를 취소됨으로 변경
		message.updateStatus(REQUEST_CANCELLED);
		aiChatMessageRepository.save(message);

		// 변경된 메시지 응답(이미지 유무에 따른 응답 생성)
		return ChatMessageResponse.createResponse(message);
	}

	// 채팅 메시지 페이징 조회
	@Transactional(readOnly = true)
	public PageResponse<ChatMessageResponse> getMessages(
		final Long memberId,
		int page, // page는 -1인 경우 마지막 페이지 조회로 변경됨
		final int size
	) {
		// page가 -1인 경우 마지막 페이지로 변환
		if (page == -1) {
			// 마지막 페이지 조회를 위한 총 메시지 개수 계산
			long totalMessages = aiChatMessageRepository.countByMemberId(memberId);
			page = (int)((totalMessages - 1) / size); // 0-based index
			if (page < 0) {
				page = 0; // 메시지가 없는 경우 첫 페이지로 설정
			}
		}

		// Page 조회
		Pageable pageable = PageRequest.of(page, size);
		Page<AiChatMessageEntity> messagePage = aiChatMessageRepository
			.findByMemberIdOrderByMessageOrderAsc(memberId, pageable);

		// 엔티티 → DTO 변환
		Page<ChatMessageResponse> responsePage = messagePage.map(ChatMessageResponse::createResponse);

		// PageResponse로 감싸서 반환
		return PageResponse.of(responsePage);
	}
}