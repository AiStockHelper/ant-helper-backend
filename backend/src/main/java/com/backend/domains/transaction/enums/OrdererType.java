package com.backend.domains.transaction.enums;

// 누가 거래 당사자인지
public enum OrdererType{
	USER, // AI가 건드리지 않는 주식
	AI, // AI가 관리하는 주식
	USER_BUT_HALF_AI, // AI가 반자동 매매
}
