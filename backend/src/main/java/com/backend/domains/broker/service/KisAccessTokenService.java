package com.backend.domains.broker.service;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backend.domains.broker.KisAccessToken;
import com.backend.domains.broker.KisAccessTokenRepository;
import lombok.RequiredArgsConstructor;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class KisAccessTokenService {

	private final KisAccessTokenRepository kisAccessTokenRepository;

	public Optional<KisAccessToken> findKisAccessToken(Long memberId) {
		return kisAccessTokenRepository.findById(memberId);
	}

	public void saveKisAccessToken(KisAccessToken kisAccessToken) {
		kisAccessTokenRepository.save(kisAccessToken);
	}

	public void deleteKisAccessToken(KisAccessToken kisAccessToken) {
		kisAccessTokenRepository.delete(kisAccessToken);
	}
}
