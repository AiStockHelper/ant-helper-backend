package com.backend.domains.member.dto.request;

import com.backend.order.kis.enums.AccountType;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor
public class CreateMemberRequest {

	@NotBlank(message = "token은 비어있을 수 없습니다.")
	private String token;

	@NotBlank(message = "email은 비어있을 수 없습니다.")
	@Email(message = "email 형식이 올바르지 않습니다.")
	private String email;

	@Setter
	@NotBlank(message = "pw는 비어있을 수 없습니다.")
	private String pw;
}

