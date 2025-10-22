package com.backend.domains.email.service;

import static com.backend.domains.email.dto.EmailDTO.*;

import java.util.Objects;
import java.util.Random;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backend.common.exception.ApiException;
import com.backend.common.exception.ErrorCode;
import com.backend.domains.email.EmailUtil;
import com.backend.domains.email.domain.EmailCode;
import com.backend.domains.email.repository.EmailCodeRepository;
import com.backend.domains.member.service.MemberService;
import lombok.RequiredArgsConstructor;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class EmailVerificationService {

	private final EmailTokenService emailTokenService;
	private final EmailCodeRepository emailCodeRepository;
	private final MemberService memberService;
	private final EmailUtil emailUtil;

	private static final String EMAIL_TITLE = "ANT HELPER 이메일 인증 코드";
	private static final String EMAIL_TEXT = "인증 코드는 %d 입니다.";

	// 이메일 인증번호 보내기
	@Transactional(rollbackFor = ApiException.class)
	public void validateEmailAndSendEmailVerification(SendVerificationCodeRequest request) {
		String email = request.getEmail();

		memberService.validateDuplicatedEmail(email);

		// 본문
		Integer verificationCode = createVerificationCode();
		String text = String.format(EMAIL_TEXT, verificationCode);

		// 이메일 코드 저장
		checkEmailCodeDuplicationAndSaveEmailCode(email, verificationCode);

		// 이메일 보내기
		emailUtil.sendEmail(email, EMAIL_TITLE, text);
	}

	// 이메일 인증 확인
	@Transactional(rollbackFor = ApiException.class)
	public VerifyEmailCodeResponse verifyEmailCode(VerifyEmailCodeRequest request) {
		String email = request.getEmail();
		Integer requestCode = request.getCode();

		// 이메일로 인증 코드 검색
		EmailCode emailCode = emailCodeRepository.findByEmail(email)
			.orElseThrow(() -> ApiException.from(ErrorCode.UNAUTHENTICATED_EMAIL));

		// 인증 코드 비교 후 인증된 이메일 저장
		if (!Objects.equals(emailCode.getCode(), requestCode)) {
			throw ApiException.from(ErrorCode.INVALID_EMAIL_CODE);
		}

		// 토큰 발급
		String token = UUID.randomUUID().toString();
		emailTokenService.saveEmailToken(token);
		return new VerifyEmailCodeResponse(token);
	}

	// 인증 코드 생성
	private Integer createVerificationCode() {
		Random random = new Random();

		return random.nextInt(900000) + 100000; // 100000부터 999999까지의 숫자 생성
	}

	// 이메일 코드 중복 확인 및 저장
	@Transactional
	public void checkEmailCodeDuplicationAndSaveEmailCode(String email, Integer verificationCode) {
		EmailCode emailCode = emailCodeRepository.findByEmail(email).orElse(null);

		// 1. 이미 저장된 emailCode가 있으면 code만 변경
		// 2. 없으면 새로 생성
		if (emailCode != null) {
			emailCode.setCode(verificationCode);
		} else {
			emailCode = new EmailCode(email, verificationCode);
		}

		emailCodeRepository.save(emailCode);
	}
}
