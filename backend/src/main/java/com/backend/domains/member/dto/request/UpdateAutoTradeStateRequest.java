package com.backend.domains.member.dto.request;

import com.backend.domains.member.enums.AutoTradeState;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class UpdateAutoTradeStateRequest {

	private AutoTradeState autoTradeState;
}