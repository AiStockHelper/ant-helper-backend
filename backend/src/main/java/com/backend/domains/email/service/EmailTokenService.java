package com.backend.domains.email.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backend.common.exception.ApiException;
import com.backend.common.exception.ErrorCode;
import com.backend.domains.email.domain.EmailToken;
import com.backend.domains.email.repository.EmailTokenRepository;
import lombok.RequiredArgsConstructor;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class EmailTokenService {
	private final EmailTokenRepository emailTokenRepository;

	// 인증된 이메일 저장
	@Transactional
	public void saveEmailToken(String token) {
		EmailToken emailToken = new EmailToken(token);
		emailTokenRepository.save(emailToken);
	}

	// 인증된 토큰 삭제
	@Transactional
	public void deleteEmailToken(String token) {
		emailTokenRepository
			.deleteById(token);
	}

	// 이메일 토큰 확인 및 삭제
	public void verifyEmailToken(String token) {
		if (!isTokenValid(token)) {
			throw ApiException.from(ErrorCode.UNAUTHENTICATED_EMAIL);
		}
		deleteEmailToken(token);
	}

	// 토큰의 유효성을 확인하는 메서드
	private boolean isTokenValid(String token) {
		return emailTokenRepository
			.findById(token)
			.isPresent();
	}
}