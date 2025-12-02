package com.backend.domains.aiChat.service;

import static com.backend.common.exception.ErrorCode.*;
import static com.backend.domains.aiChat.enums.AiChatStatus.*;
import static com.backend.domains.aiChat.enums.SenderType.*;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backend.common.exception.ApiException;
import com.backend.domains.aiChat.dto.response.ChatMessageResponse;
import com.backend.domains.aiChat.entity.AiChatMessageEntity;
import com.backend.domains.aiChat.enums.AiChatStatus;
import com.backend.domains.aiChat.repository.AiChatMessageRepository;
import com.backend.domains.watchList.WatchListService;
import com.backend.infra.ai.chat.dto.llm.gpt.GptChatResponseDto;
import com.backend.order.domestic.service.DomesticStockService;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor(access = AccessLevel.PROTECTED)
public class AiServerService {

	// message 관련
	private final AiResponseSseService aiResponseSseService;
	private final AiChatMessageRepository aiChatMessageRepository;
	private final AiChatMessageOrderService aiChatMessageOrderService;

	// aiChatRoom 관련
	private final AiChatRoomService aiChatRoomService;

	// 채팅 관련
	private final ChatClient chatClient;

	// 국내 주식 관련
	private final DomesticStockService domesticStockService;

	// watchList 관련
	private final WatchListService watchListService;

	private final ObjectMapper objectMapper;

	// AiAgent를 통해 해당 메시지 채팅응답용인지, 이미지 생성용인지 구분 후 처리
	// 빠른 응답을 위해 비동기 처리, 응답은 SSE를 통해 클라이언트에 전달
	@Transactional
	@Async("llmTaskExecutor")
	public void processAiRequest(
		final long memberId,
		final long memberAccountId,
		final long requestMessageId,
		final String prompt
	) {
		// 1.AI에게 채팅 생성 요청
		GptChatResponseDto gptChatResponse = requestChatPrompt(
			memberId,
			memberAccountId,
			prompt,
			aiChatRoomService.getChatRoom(memberId).chatSummary()
		);

		// 2.채팅 저장
		// 다음 메시지 순서 조회
		AiChatMessageEntity requestChatMessage = aiChatMessageRepository.findById(requestMessageId)
			.orElseThrow(() -> new ApiException(AI_CHAT_MESSAGE_NOT_FOUND));
		final Long messageOrder = aiChatMessageOrderService.nextMessageOrder(requestChatMessage.getAiChatRoomId());

		// 메시지 저장
		AiChatMessageEntity message = AiChatMessageEntity.builder()
			.memberId(memberId)
			.aiChatRoomId(requestChatMessage.getAiChatRoomId())
			.messageOrder(messageOrder)
			.senderType(AI)
			.textContent(gptChatResponse.response())
			.requestId(requestChatMessage.getRequestId())
			.status(RESPONSE)
			.build();
		aiChatMessageRepository.save(message);

		// 3. 요청 메세지 상태 변경
		requestChatMessage.updateStatus(AiChatStatus.REQUEST_REPLIED);
		aiChatMessageRepository.save(requestChatMessage);

		// 4.채팅룸 요약 업데이트
		aiChatRoomService.updateChatSummary(memberId, gptChatResponse.newSummary());

		// 5. SSE로 실시간 응답
		aiResponseSseService.sendToClient(
			requestChatMessage.getRequestId(),
			ChatMessageResponse.createResponse(message)
		);
	}

	// AI에게 채팅 생성 요청
	// TODO : 요약을 추후 VECTOR DB로 변경 고려
	private GptChatResponseDto requestChatPrompt(
		final long memberId,
		final long memberAccountId,
		final String textContent,
		final String chatSummary
	) {
		final String systemPrompt = """
			You are a helpful AI assistant.
			- Use the given chat summary as context.
			- Respond naturally to the new user message.
			- Also update the chat summary by including this new interaction.
			- Speak Korean.
			
			Return the result strictly as raw JSON object.
			Do not include Markdown formatting, code fences, or extra text.
			Only output JSON with two fields: response and newSummary.
			""";
		final String jsonResponse = chatClient.prompt()
			.system(systemPrompt)
			.user("Chat summary so far: " + chatSummary + "\nUser message: " + textContent + "memberAccountId:"
				+ memberAccountId + ", memberId:" + memberId)
			.tools(domesticStockService, watchListService)
			.call()
			.content();

		try {
			return objectMapper.readValue(jsonResponse, GptChatResponseDto.class);
		} catch (Exception e) {
			log.error("Failed to parse AI chat response. Raw response: {}", jsonResponse, e);
			throw new ApiException(AI_REQUEST_FAILED);
		}
	}
}