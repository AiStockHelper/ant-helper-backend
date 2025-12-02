package com.backend.domains.aiChat.service;

import static com.backend.common.exception.ErrorCode.*;

import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.backend.common.exception.ApiException;
import com.backend.domains.aiChat.entity.AiChatMessageEntity;
import com.backend.domains.aiChat.enums.SenderType;
import com.backend.domains.aiChat.repository.AiChatMessageRepository;
import com.backend.infra.sse.service.EmitterService;

import lombok.RequiredArgsConstructor;

// 유저의 요청에 대한 응답을 SSE로 전송하는 서비스
@Service
@RequiredArgsConstructor
public class AiResponseSseService {

	private final EmitterService emitterService;
	private final AiChatMessageRepository aiChatMessageRepository;

	// requestId와 SseEmitter를 ,emitter관리 맵에 저장
	public void addEmitter(Long memberId, String requestId, SseEmitter emitter) {
		// 요청된 requestId에 해당하는 유저 요청이 존재하는 지 확인
		AiChatMessageEntity request = aiChatMessageRepository.findByRequestIdAndSenderType(requestId, SenderType.USER)
			.orElseThrow(() -> new ApiException(AI_CHAT_MESSAGE_NOT_FOUND));

		// sse 연결하려는 사람이 요청 유저인지 확인
		if (!Objects.equals(request.getMemberId(), memberId)) {
			throw new ApiException(FORBIDDEN);
		}

		// Emitter 등록
		emitterService.addEmitter(requestId, emitter);
	}

	// requestId에 해당하는 SseEmitter를 맵에서 제거
	public void removeEmitter(String requestId) {
		emitterService.removeEmitter(requestId);
	}

	// emitters에서 연결객체를 찾아 해당 클라이언트에게 데이터를 SSE로 전송, 전송 후 연결 삭제
	public void sendToClient(String requestId, Object data) {
		emitterService.sendToClient(requestId, data);
	}
}
