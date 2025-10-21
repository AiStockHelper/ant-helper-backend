package com.backend.domains.member.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import com.backend.domains.member.domain.AutoTradeState;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

public class MemberDTO {

	@Getter
	@NoArgsConstructor(access = AccessLevel.PRIVATE)
	@AllArgsConstructor
	public static class CreateMemberRequest {

		@NotBlank(message = "token은 비어있을 수 없습니다.")
		private String token;

		@NotBlank(message = "email은 비어있을 수 없습니다.")
		@Email(message = "email 형식이 올바르지 않습니다.")
		private String email;

		@Setter
		@NotBlank(message = "pw는 비어있을 수 없습니다.")
		private String pw;

		@NotBlank(message = "appkey은 비어있을 수 없습니다.")
		private String appKey;

		@NotBlank(message = "secretkey은 비어있을 수 없습니다.")
		private String secretKey;

		@NotBlank
		@Size(min = 8, max = 8, message = "comprehensiveAccountNumber은 8자리여야 합니다.")
		private String comprehensiveAccountNumber;

		@NotBlank
		@Size(min = 2, max = 2, message = "accountProductCode은 2자리여야 합니다.")
		private String accountProductCode;
	}

	@AllArgsConstructor
	public static class LoginRequest {

		public String email;
		public String pw;
	}

	@Getter
	@NoArgsConstructor(access = AccessLevel.PRIVATE)
	public static class UpdateAutoTradeStateRequest {

		private AutoTradeState autoTradeState;
	}
}
