package com.backend.domains.auth.dto;

import java.time.OffsetDateTime;

import com.backend.domains.member.domain.Member;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AuthDtos {

	private AuthDtos() {
	}

	public record SignupRequest(
		@NotBlank(message = "이메일은 필수입니다.") @Email(message = "이메일 형식이 올바르지 않습니다.") String email,
		@NotBlank(message = "비밀번호는 필수입니다.") @Size(min = 8, max = 100, message = "비밀번호는 8자 이상이어야 합니다.") String password,
		@Size(max = 50, message = "닉네임은 50자 이하여야 합니다.") String nickname,
		String emailToken
	) {
	}

	public record LoginRequest(
		@NotBlank(message = "이메일은 필수입니다.") String email,
		@NotBlank(message = "비밀번호는 필수입니다.") String password
	) {
	}

	public record RefreshRequest(@NotBlank(message = "refreshToken은 필수입니다.") String refreshToken) {
	}

	public record UpdateMeRequest(
		@NotBlank(message = "닉네임은 필수입니다.") @Size(max = 50, message = "닉네임은 50자 이하여야 합니다.") String nickname
	) {
	}

	public record Me(Long id, String email, String nickname, OffsetDateTime createdAt) {
		public static Me from(Member m) {
			return new Me(m.getId(), m.getEmail(), m.displayName(), com.backend.common.util.TimeUtil.kst(m.getCreatedAt()));
		}
	}

	public record AuthTokens(String accessToken, String refreshToken, long expiresIn, Me member) {
	}
}
