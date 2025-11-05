package com.backend.domains.member.controller;

import static com.backend.common.exception.ErrorCode.*;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.backend.common.dto.DataResponse;
import com.backend.common.security.filter.jwtFilter.JwtTokenProvider;
import com.backend.common.swagger.ApiErrorMapping;
import com.backend.domains.member.dto.request.CreateMemberRequest;
import com.backend.domains.member.service.MemberService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Tag(name = "MEMBER API", description = "회원에 대한 API입니다.")
@RestController
@RequestMapping("/api/members")
@RequiredArgsConstructor
public class MemberController {

	private final MemberService memberService;
	private final JwtTokenProvider jwtTokenProvider;

	@PostMapping("/signup")
	@Operation(
		summary = "회원가입",
		description = "사용자 이름, 비밀번호, 이메일, appKey, secretKey, 계좌번호, 뒷자리(2자리)를 사용하여 회원가입"
	)
	@ApiErrorMapping({UNAUTHENTICATED_EMAIL, EMAIL_DUPLICATE})
	public ResponseEntity<DataResponse<Void>> createMember(@RequestBody @Valid CreateMemberRequest request) {
		memberService.createMember(request);

		return ResponseEntity.ok(DataResponse.ok());
	}

	@PostMapping(value = "/login", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
	@Operation(
		summary = "로그인",
		description = "로그인 성공 시 accessToken, refreshToken을 반환"
	)
	public ResponseEntity<DataResponse<Void>> loginMember(
		@RequestParam("email") String email,
		@RequestParam("pw") String pw) {
		// 이 메소드는 실제로 실행되지 않습니다. 문서용도로만 사용됩니다.
		return ResponseEntity.ok(DataResponse.ok());
	}

	@DeleteMapping
	@Operation(
		summary = "회원 탈퇴",
		description = "회원 탈퇴"
	)
	public ResponseEntity<DataResponse<Void>> deleteMember(
		@AuthenticationPrincipal Long memberId
	) {
		memberService.deleteMember(memberId);

		return ResponseEntity.ok(DataResponse.ok());
	}

	@PostMapping("/logout")
	@Operation(
		summary = "로그아웃",
		description = "로그아웃"
	)
	public ResponseEntity<DataResponse<Void>> logoutMember(
		@AuthenticationPrincipal Long memberId,
		HttpServletRequest request
	) {
		String accessToken = jwtTokenProvider.extractAccessToken(request).orElse(null);

		memberService.logoutMember(memberId, accessToken);

		return ResponseEntity.ok(DataResponse.ok());
	}
}
