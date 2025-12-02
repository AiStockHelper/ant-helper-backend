package com.backend.domains.aiChat.service;

import static com.backend.common.exception.ErrorCode.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backend.common.exception.ApiException;
import com.backend.domains.aiChat.dto.ChatContextResetResponse;
import com.backend.domains.aiChat.dto.response.GetChatRoomResponse;
import com.backend.domains.aiChat.entity.AiChatRoomEntity;
import com.backend.domains.aiChat.repository.AiChatRoomRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiChatRoomService {

	private final AiChatRoomRepository aiChatRoomRepository;

	// 채팅방 생성
	@Transactional
	public GetChatRoomResponse createChatRoom(final long memberId) {
		// 없는 경우에만 저장
		aiChatRoomRepository.insertIgnore(memberId);

		// 채팅방 정보 조회 및 응답
		AiChatRoomEntity room = aiChatRoomRepository.findByMemberId(memberId).get();
		return GetChatRoomResponse.from(room);
	}

	// 채팅방 정보 조회 (AI 이미지 생성권 = 채팅방 입장권)
	@Transactional(readOnly = true)
	public GetChatRoomResponse getChatRoom(final long memberId) {
		AiChatRoomEntity aiChatRoom = aiChatRoomRepository.findByMemberId(memberId)
			.orElseThrow(() -> new ApiException(AI_CHAT_ROOM_NOT_FOUND));
		return GetChatRoomResponse.from(aiChatRoom);
	}

	// 채팅 컨텍스트 초기화
	@Transactional
	public ChatContextResetResponse resetContext(final long memberId) {
		AiChatRoomEntity permission = aiChatRoomRepository
			.findByMemberId(memberId)
			.orElseThrow(() -> new ApiException(AI_CHAT_ROOM_NOT_FOUND));

		permission.resetContext();
		aiChatRoomRepository.save(permission);

		return new ChatContextResetResponse(
			permission.getId(),
			permission.getContextResetAt()
		);
	}

	@Transactional
	public void updateChatSummary(final long memberId, String s) {
		AiChatRoomEntity aiChatRoom = aiChatRoomRepository.findByMemberId(memberId)
			.orElseThrow(() -> new ApiException(AI_CHAT_ROOM_NOT_FOUND));
		aiChatRoom.updateChatSummary(s);
		aiChatRoomRepository.save(aiChatRoom);
	}
}