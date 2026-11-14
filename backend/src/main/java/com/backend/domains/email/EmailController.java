package com.backend.domains.email;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import com.backend.common.dto.DataResponse;
import com.backend.common.dto.ErrorResponse;
import com.backend.domains.email.dto.EmailDTO.SendVerificationCodeRequest;
import com.backend.domains.email.dto.EmailDTO.VerifyEmailCodeRequest;
import com.backend.domains.email.dto.EmailDTO.VerifyEmailCodeResponse;
import com.backend.domains.email.service.EmailVerificationService;
import lombok.RequiredArgsConstructor;

@Tag(name = "EMAIL API", description = "이메일에 대한 API입니다.")
@RestController
@RequestMapping("/api/email")
@RequiredArgsConstructor
public class EmailController {

	private final EmailVerificationService emailVerificationService;

	@PostMapping("/verification-request")
	@Operation(
		summary = "email 인증 요청",
		description = "email 인증을 위한 이메일을 전송",
		responses = {
			@ApiResponse(
				responseCode = "200",
				description = "성공"
			),
			@ApiResponse(
				responseCode = "409",
				description = "이미 가입된 이메일입니다.",
				content = @Content(schema = @Schema(implementation = ErrorResponse.class))
			)
		}
	)
	public ResponseEntity<DataResponse<Void>> sendVerificationCode(
		@RequestBody @Valid SendVerificationCodeRequest request
	) {
		emailVerificationService.validateEmailAndSendEmailVerification(request);

		return ResponseEntity.ok(DataResponse.ok());
	}

	@PostMapping("/verification")
	@Operation(
		summary = "email 인증 확인",
		description = """
			인증 요청을 한 email이 없으면 401
			code가 일치하면 true, 일치하지 않으면 false를 반환한다.""",
		responses = {
			@ApiResponse(
				responseCode = "200",
				description = "성공"
			),
			@ApiResponse(
				responseCode = "400",
				description = "유효하지 않은 이메일 코드입니다.",
				content = @Content(schema = @Schema(implementation = ErrorResponse.class))
			),
			@ApiResponse(
				responseCode = "401",
				description = "이메일 인증을 시도해주세요.",
				content = @Content(schema = @Schema(implementation = ErrorResponse.class))
			)
		}
	)
	public ResponseEntity<DataResponse<VerifyEmailCodeResponse>> verifyEmailCode(
		@RequestBody @Valid VerifyEmailCodeRequest request
	) {
		VerifyEmailCodeResponse response = emailVerificationService.verifyEmailCode(request);

		return ResponseEntity.ok(DataResponse.from(response));
	}
}
