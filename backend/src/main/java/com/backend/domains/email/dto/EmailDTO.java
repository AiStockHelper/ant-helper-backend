package com.backend.domains.email.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

public class EmailDTO {

	@Getter
	@NoArgsConstructor(access = AccessLevel.PRIVATE)
	@AllArgsConstructor
	public static class SendVerificationCodeRequest {

		@NotBlank(message = "email은 비어있을 수 없습니다.")
		@Email(message = "email 형식이 올바르지 않습니다.")
		private String email;
	}

	@Getter
	@NoArgsConstructor(access = AccessLevel.PRIVATE)
	public static class VerifyEmailCodeRequest {

		@NotBlank(message = "email은 비어있을 수 없습니다.")
		@Email(message = "email 형식이 올바르지 않습니다.")
		private String email;

		private Integer code;
	}

	@Getter
	@AllArgsConstructor
	public static class VerifyEmailCodeResponse {

		private String token;
	}
}
