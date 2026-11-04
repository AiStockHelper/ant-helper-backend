package com.backend.market;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.backend.domains.member.domain.MemberAccount;
import com.backend.order.kis.kis_client.KisClient;
import com.backend.order.kis.kis_client.api.Api;
import com.backend.order.kis.kis_client.api.ApiResult;
import com.backend.order.kis.kis_client.config.Configuration;
import com.backend.order.kis.kis_client.config.Credentials;
import com.backend.order.kis.kis_client.exception.KisClientException;

import lombok.extern.slf4j.Slf4j;

/**
 * 계좌별 KisClient 를 만들고 캐시하며, KIS 응답 코드(rt_cd)를 한 곳에서 검사한다.
 * 이 클래스를 거치면 호출자는 사용자 키/시크릿을 직접 다루지 않는다.
 */
@Slf4j
@Component
public class KisGateway {

	private record Cached(String fingerprint, KisClient client) {
	}

	private final Map<Long, Cached> clients = new ConcurrentHashMap<>();

	/** 개발 전용: 값이 있으면 모든 KIS 호출을 이 주소(목 서버)로 보낸다. 운영에서는 비워 둔다. */
	@org.springframework.beans.factory.annotation.Value("${app.kis.base-url-override:}")
	private String baseUrlOverride;

	public KisClient clientFor(MemberAccount account) {
		String fingerprint = account.getAppKey() + "|" + account.getSecretKey() + "|" + account.getAccountType();
		Cached cached = clients.get(account.getId());
		if (cached != null && cached.fingerprint().equals(fingerprint)) {
			return cached.client();
		}
		KisClient client = create(
			account.getDecryptedAppKey(),
			account.getDecryptedSecretKey(),
			account.getAccountNumber(),
			account.getAccountProductCode(),
			account.isPaper()
		);
		clients.put(account.getId(), new Cached(fingerprint, client));
		return client;
	}

	/** 아직 저장되지 않은 키로 임시 클라이언트를 만든다(계좌 등록 검증용). */
	public KisClient temporaryClient(String appKey, String secretKey, String accountNumber, String productCode,
		boolean paper) {
		return create(appKey, secretKey, accountNumber, productCode, paper);
	}

	public void evict(Long accountId) {
		Cached removed = clients.remove(accountId);
		if (removed != null) {
			try {
				removed.client().close();
			} catch (Exception e) {
				log.debug("KisClient close 실패: {}", e.getMessage());
			}
		}
	}

	public <T extends ApiResult> T execute(MemberAccount account, Api<T> api) {
		long start = System.currentTimeMillis();
		String apiName = api.getClass().getSimpleName();
		try {
			T result = check(clientFor(account).execute(api));
			log.info("KIS 호출 성공 account={} api={} {}ms", account.getId(), apiName, System.currentTimeMillis() - start);
			return result;
		} catch (KisClientException e) {
			log.warn("KIS 호출 실패 account={} paper={} api={} {}ms: {}", account.getId(), account.isPaper(), apiName,
				System.currentTimeMillis() - start, e.getMessage());
			throw e;
		} catch (RuntimeException e) {
			log.error("KIS 호출 중 예외 account={} api={} {}ms: {}", account.getId(), apiName,
				System.currentTimeMillis() - start, e.toString(), e);
			throw e;
		}
	}

	public <T extends ApiResult> T execute(KisClient client, Api<T> api) {
		return check(client.execute(api));
	}

	private KisClient create(String appKey, String secretKey, String accountNumber, String productCode,
		boolean paper) {
		Credentials credentials = new Credentials(appKey, secretKey, accountNumber, productCode);
		// 모의투자 서버는 초당 호출 제한이 훨씬 낮다.
		credentials.setRestLimitPerSecond(paper ? 2 : 15);
		Configuration config = new Configuration(
			paper ? com.backend.order.kis.enums.AccountType.PAPER_TRADE : com.backend.order.kis.enums.AccountType.REAL_TRADE);
		if (baseUrlOverride != null && !baseUrlOverride.isBlank()) {
			config.setHttpHost(baseUrlOverride.trim());
		}
		config.addCredentials(credentials);
		return new KisClient(config);
	}

	private <T extends ApiResult> T check(T result) {
		String rtCd = read(result, "getRtCd");
		if (rtCd != null && !"0".equals(rtCd)) {
			String msg = read(result, "getMsg1");
			log.warn("KIS 응답 오류 rt_cd={} msg_cd={} msg1={}", rtCd, read(result, "getMsgCd"), msg);
			throw new KisClientException(msg == null || msg.isBlank() ? "한국투자증권 요청이 실패했습니다." : msg);
		}
		return result;
	}

	private String read(Object target, String getter) {
		try {
			Method m = target.getClass().getMethod(getter);
			Object v = m.invoke(target);
			return v == null ? null : v.toString();
		} catch (ReflectiveOperationException e) {
			return null;
		}
	}
}
