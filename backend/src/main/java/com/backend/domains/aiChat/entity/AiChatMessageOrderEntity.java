package com.backend.domains.aiChat.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "ai_chat_message_orders",
	uniqueConstraints = @UniqueConstraint(columnNames = {"ai_chat_room_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiChatMessageOrderEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "ai_chat_room_id", nullable = false)
	private Long aiChatRoomId;

	@Column(name = "last_order", nullable = false)
	private Long lastOrder;

	public AiChatMessageOrderEntity(Long aiChatRoomId, Long lastOrder) {
		this.aiChatRoomId = aiChatRoomId;
		this.lastOrder = lastOrder;
	}

	/** 순번 증가 */
	public void increment() {
		this.lastOrder = this.lastOrder + 1;
	}
}