package com.backend.domains.recaptcha.dto;

import static lombok.AccessLevel.*;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

public class RecaptchaDTO {

	@Getter
	@NoArgsConstructor(access = PRIVATE)
	public static class ValidateRecaptchaRequest {

		@NotBlank
		private String token;
	}

	@Getter
	@NoArgsConstructor(access = PRIVATE)
	public static class ValidateRecaptchaResponse {

		private boolean success;
	}
}
