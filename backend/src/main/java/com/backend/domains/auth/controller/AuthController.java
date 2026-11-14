package com.backend.domains.auth.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.backend.common.dto.DataResponse;
import com.backend.common.security.filter.jwtFilter.JwtTokenProvider;
import com.backend.domains.auth.dto.AuthDtos.AuthTokens;
import com.backend.domains.auth.dto.AuthDtos.LoginChallenge;
import com.backend.domains.auth.dto.AuthDtos.StepUpChallenge;
import com.backend.domains.auth.dto.AuthDtos.StepUpVerifyRequest;
import com.backend.domains.auth.dto.AuthDtos.VerifyLoginRequest;
import com.backend.domains.auth.otp.OtpService.Issued;
import com.backend.domains.auth.otp.StepUpService;
import com.backend.domains.auth.dto.AuthDtos.LoginRequest;
import com.backend.domains.auth.dto.AuthDtos.Me;
import com.backend.domains.auth.dto.AuthDtos.RefreshRequest;
import com.backend.domains.auth.dto.AuthDtos.SignupRequest;
import com.backend.domains.auth.dto.AuthDtos.UpdateMeRequest;
import com.backend.domains.auth.service.AuthService;
import com.backend.domains.member.service.MemberService;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Tag(name = "AUTH API", description = "회원가입 / 로그인 / 토큰 재발급 / 내 정보")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

	private final AuthService authService;
	private final MemberService memberService;
	private final JwtTokenProvider jwtTokenProvider;
	private final StepUpService stepUp;

	@PostMapping("/signup")
	public ResponseEntity<DataResponse<Me>> signup(@RequestBody @Valid SignupRequest request) {
		return ResponseEntity.ok(DataResponse.from(authService.signup(request)));
	}

	@PostMapping("/login")
	public ResponseEntity<DataResponse<LoginChallenge>> login(@RequestBody @Valid LoginRequest request, HttpServletRequest http) {
		return ResponseEntity.ok(DataResponse.from(authService.login(request, http.getRemoteAddr())));
	}

	@PostMapping("/login/verify")
	public ResponseEntity<DataResponse<AuthTokens>> verifyLogin(@RequestBody @Valid VerifyLoginRequest request,
		HttpServletRequest http) {
		return ResponseEntity.ok(DataResponse.from(authService.verifyLogin(request, http.getRemoteAddr())));
	}

	@PostMapping("/refresh")
	public ResponseEntity<DataResponse<AuthTokens>> refresh(@RequestBody @Valid RefreshRequest request, HttpServletRequest http) {
		return ResponseEntity.ok(DataResponse.from(authService.refresh(request.refreshToken(), http.getRemoteAddr())));
	}

	@PostMapping("/logout")
	public ResponseEntity<DataResponse<Void>> logout(@AuthenticationPrincipal Long memberId, HttpServletRequest request) {
		String accessToken = jwtTokenProvider.extractAccessToken(request).orElse(null);
		authService.logout(memberId, accessToken, request.getRemoteAddr());
		return ResponseEntity.ok(DataResponse.ok());
	}

	/** 실계좌 보호: 민감한 작업 전에 이메일 인증번호를 요청한다. */
	@PostMapping("/step-up/request")
	public ResponseEntity<DataResponse<StepUpChallenge>> stepUpRequest(@AuthenticationPrincipal Long memberId,
		HttpServletRequest http) {
		Issued i = stepUp.request(memberId, http.getRemoteAddr());
		return ResponseEntity.ok(DataResponse.from(
			new StepUpChallenge(i.challengeId(), i.expiresInSeconds(), i.resendAfterSeconds(), stepUp.validMinutes())));
	}

	@PostMapping("/step-up/verify")
	public ResponseEntity<DataResponse<Void>> stepUpVerify(@AuthenticationPrincipal Long memberId,
		@RequestBody @Valid StepUpVerifyRequest request, HttpServletRequest http) {
		stepUp.verify(memberId, request.challengeId(), request.code(), http.getRemoteAddr());
		return ResponseEntity.ok(DataResponse.ok());
	}
	@GetMapping("/me")
	public ResponseEntity<DataResponse<Me>> me(@AuthenticationPrincipal Long memberId) {
		return ResponseEntity.ok(DataResponse.from(authService.me(memberId)));
	}

	@PatchMapping("/me")
	public ResponseEntity<DataResponse<Me>> updateMe(
		@AuthenticationPrincipal Long memberId,
		@RequestBody @Valid UpdateMeRequest request
	) {
		return ResponseEntity.ok(DataResponse.from(authService.updateNickname(memberId, request.nickname())));
	}

	@DeleteMapping("/me")
	public ResponseEntity<DataResponse<Void>> deleteMe(@AuthenticationPrincipal Long memberId) {
		authService.deleteMember(memberId);
		return ResponseEntity.ok(DataResponse.ok());
	}
}
