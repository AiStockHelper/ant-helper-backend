package com.backend.domains.broker.dto;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

public class BrokerDTO {

	@Getter
	@AllArgsConstructor
	public static class GetAccessKeyRequest {

		private String appkey;
		private String secretkey;
		private String grant_type;
	}

	@Getter
	@NoArgsConstructor(access = AccessLevel.PRIVATE)
	public static class GetAccessKeyResponse {

		private String approval_key;
	}

	@Getter
	@AllArgsConstructor
	public static class GetAccessTokenRequest {

		private String appkey;
		private String appsecret;
		private String grant_type;
	}

	@Getter
	@NoArgsConstructor(access = AccessLevel.PRIVATE)
	public static class GetAccessTokenResponse {

		private String access_token;
		private String token_type;
		private int expires_in;
		private String access_token_token_expired;
	}

	@Getter
	@NoArgsConstructor(access = AccessLevel.PRIVATE)
	public static class KisErrorResponse {
		private String rt_cd;
		private String msg_cd;
		private String msg1;
	}
}
