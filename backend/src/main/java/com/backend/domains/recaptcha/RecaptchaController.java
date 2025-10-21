package com.backend.domains.recaptcha;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import com.backend.common.dto.DataResponse;
import com.backend.common.dto.ErrorResponse;
import com.backend.domains.recaptcha.dto.RecaptchaDTO.ValidateRecaptchaRequest;
import com.backend.domains.recaptcha.dto.RecaptchaDTO.ValidateRecaptchaResponse;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/recaptcha")
@RequiredArgsConstructor
public class RecaptchaController {

	private final RecaptchaService recaptchaService;

	@PostMapping("/validate")
	@Operation(
		summary = "reCAPTCHA 검증",
		description = """
			reCAPTCHA 검증 api 입니다.""",
		responses = {
			@ApiResponse(
				responseCode = "200",
				description = "성공"
			),
			@ApiResponse(
				responseCode = "400",
				description = "요청 파라미터가 잘 못 되었습니다.",
				content = @Content(schema = @Schema(implementation = ErrorResponse.class))
			),
			@ApiResponse(
				responseCode = "500",
				description = "이메일 전송에 실패하였습니다.",
				content = @Content(schema = @Schema(implementation = ErrorResponse.class))
			)
		}
	)
	public ResponseEntity<DataResponse<ValidateRecaptchaResponse>> validateRecaptcha(
		@RequestBody @Valid ValidateRecaptchaRequest request) {
		ValidateRecaptchaResponse response = recaptchaService.validateRecaptcha(request);

		return ResponseEntity.ok(DataResponse.from(response));
	}
}
