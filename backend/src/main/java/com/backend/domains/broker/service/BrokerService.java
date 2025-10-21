package com.backend.domains.broker.service;

import static com.backend.domains.broker.dto.BrokerDTO.*;

import java.util.HashMap;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backend.common.util.webClient.WebClientUtil;
import com.backend.domains.broker.KisAccessToken;
import com.backend.domains.member.MemberService;
import com.backend.domains.member.domain.Member;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class BrokerService {

	private final MemberService memberService;
	private final KisAccessTokenService kisAccessTokenService;
	private final WebClientUtil webClientUtil;

	// 한국 투자 증권에서 Approval Key를 받아와서 저장하는 메서드
	@Transactional
	public void getAndSaveApprovalKey(Member member) {
		String appKey = memberService.getDecryptedAppKey(member);
		String secretKey = memberService.getDecryptedSecretKey(member);

		GetAccessKeyResponse approvalKey = getApprovalKey(appKey, secretKey);

		memberService.updateApprovalKey(member, approvalKey.getApproval_key());
	}

	// Approval Key를 받아오는 메서드
	private GetAccessKeyResponse getApprovalKey(String appKey, String secretKey) {
		String url = "/oauth2/Approval";
		String grantType = "client_credentials";

		GetAccessKeyRequest request = new GetAccessKeyRequest(appKey, secretKey, grantType);

		return webClientUtil.postFromKis(new HashMap<>(), url, request, GetAccessKeyResponse.class);
	}

	// 한국 투자 증권에서 Access Token을 받아와서 저장하는 메서드
	@Transactional
	public void getAndSaveAccessToken(Member member) {
		String BEARER = "Bearer ";

		// 기존 accessToken이 없으면 재발급
		KisAccessToken kisAccessToken = kisAccessTokenService.findKisAccessToken(member.getId()).orElse(null);
		if (kisAccessToken == null) {
			String appKey = memberService.getDecryptedAppKey(member);
			String secretKey = memberService.getDecryptedSecretKey(member);

			String accessToken = BEARER + getAccessToken(appKey, secretKey).getAccess_token();
			kisAccessToken = new KisAccessToken(member.getId(), accessToken);

			kisAccessTokenService.saveKisAccessToken(kisAccessToken);
		}
	}

	// Access Token을 받아오는 메서드
	private GetAccessTokenResponse getAccessToken(String appKey, String secretKey) {
		String url = "/oauth2/tokenP";
		String grantType = "client_credentials";

		GetAccessTokenRequest request = new GetAccessTokenRequest(appKey, secretKey, grantType);

		return webClientUtil.postFromKis(new HashMap<>(), url, request, GetAccessTokenResponse.class);
	}
}
