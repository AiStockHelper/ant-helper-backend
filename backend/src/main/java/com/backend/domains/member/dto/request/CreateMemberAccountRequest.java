package com.backend.domains.member.dto.request;

import com.backend.order.kis.enums.AccountType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor
public class CreateMemberAccountRequest {
	@NotNull(message = "accountType(계좌종류: 실전투자 or 모의투자)는 비어있을 수 없습니다.")
	private AccountType accountType;

	@NotBlank(message = "appkey은 비어있을 수 없습니다.")
	private String appKey;

	@NotBlank(message = "secretkey은 비어있을 수 없습니다.")
	private String secretKey;

	@NotBlank
	@Size(min = 8, max = 8, message = "accountNumber은 8자리여야 합니다.")
	private String accountNumber;

	@NotBlank
	@Size(min = 2, max = 2, message = "accountProductCode은 2자리여야 합니다.")
	private String accountProductCode;
}
