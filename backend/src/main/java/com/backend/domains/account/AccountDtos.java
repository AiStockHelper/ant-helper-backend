package com.backend.domains.account;

import java.time.OffsetDateTime;

import com.backend.common.util.TimeUtil;
import com.backend.domains.member.domain.MemberAccount;
import com.backend.order.kis.enums.AccountType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class AccountDtos {

	private AccountDtos() {
	}

	public record CreateAccountRequest(
		@NotNull(message = "계좌 유형(accountType)은 필수입니다.") AccountType accountType,
		@Size(max = 50, message = "별칭은 50자 이하여야 합니다.") String alias,
		@NotBlank(message = "appKey는 필수입니다.") String appKey,
		@NotBlank(message = "secretKey는 필수입니다.") String secretKey,
		@NotBlank @Pattern(regexp = "\\d{8}", message = "계좌번호는 숫자 8자리여야 합니다.") String accountNumber,
		@NotBlank @Pattern(regexp = "\\d{2}", message = "계좌상품코드는 숫자 2자리여야 합니다.") String accountProductCode
	) {
	}

	public record UpdateAccountRequest(
		@Size(max = 50, message = "별칭은 50자 이하여야 합니다.") String alias,
		Boolean isDefault
	) {
	}

	public record Account(
		Long id, AccountType accountType, String alias, String accountNumberMasked,
		String accountProductCode, boolean isDefault, OffsetDateTime createdAt
	) {
		public static Account from(MemberAccount a) {
			return new Account(a.getId(), a.getAccountType(), a.displayName(), a.maskedAccountNumber(),
				a.getAccountProductCode(), a.isDefaultAccount(), TimeUtil.kst(a.getCreatedAt()));
		}
	}

	public record AccountDetail(
		Long id, AccountType accountType, String alias, String accountNumberMasked,
		String accountProductCode, boolean isDefault, OffsetDateTime createdAt,
		String kisEnvironment, String tokenStatus
	) {
	}
}
