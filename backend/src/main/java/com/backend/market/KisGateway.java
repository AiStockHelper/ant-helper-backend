package com.backend.market;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.backend.domains.member.domain.MemberAccount;
import com.backend.market.queue.KisFailure;
import com.backend.market.queue.KisLane;
import com.backend.market.queue.KisLimits;
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
	private final Map<Long, KisLane> lanes = new ConcurrentHashMap<>();

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
		KisLane lane = lanes.remove(accountId);
		if (lane != null) {
			lane.close();
		}
	}

	/** 앱키(계좌) 단위 호출 줄. 같은 앱키의 모든 요청이 여기서 초당 한도에 맞춰 순서대로 나간다. */
	private KisLane laneFor(MemberAccount account) {
		return lanes.computeIfAbsent(account.getId(),
			id -> new KisLane("acct" + id, KisLimits.spacingMillis(account.isPaper())));
	}

	/** 조회성 호출: 줄에서 기다리다 실패하면 버려지고 예외가 호출한 쪽으로 간다. */
	public <T extends ApiResult> T execute(MemberAccount account, Api<T> api) {
		return run(account, api, KisLane.Priority.LOOKUP, KisLimits.LOOKUP_MAX_WAIT_MILLIS, KisLimits.LOOKUP_RATE_LIMIT_RETRIES);
	}

	/** 주문·정정·취소: 조회보다 먼저 나가고, 호출 한도 초과는 몇 번 더 시도한다. */
	public <T extends ApiResult> T executeOrder(MemberAccount account, Api<T> api) {
		return run(account, api, KisLane.Priority.ORDER, KisLimits.ORDER_MAX_WAIT_MILLIS, KisLimits.ORDER_RATE_LIMIT_RETRIES);
	}

	private <T extends ApiResult> T run(MemberAccount account, Api<T> api, KisLane.Priority priority, long maxWaitMillis,
		int rateLimitRetries) {
		long start = System.currentTimeMillis();
		String apiName = api.getClass().getSimpleName();
		KisLane lane = laneFor(account);
		for (int attempt = 0; ; attempt++) {
			try {
				T result = lane.call(priority, maxWaitMillis, () -> check(clientFor(account).execute(api)));
				log.info("KIS 호출 성공 account={} api={} {}ms 대기열={}", account.getId(), apiName,
					System.currentTimeMillis() - start, lane.waiting());
				return result;
			} catch (KisClientException e) {
				if (KisFailure.classify(e) == KisFailure.RATE_LIMIT && attempt < rateLimitRetries) {
					log.warn("KIS 호출 한도 초과, 다시 시도 account={} api={} ({}/{})", account.getId(), apiName, attempt + 1,
						rateLimitRetries);
					try {
						Thread.sleep(KisLimits.RATE_LIMIT_RETRY_DELAY_MILLIS);
					} catch (InterruptedException ie) {
						Thread.currentThread().interrupt();
						throw e;
					}
					continue;
				}
				log.warn("KIS 호출 실패 account={} paper={} api={} {}ms: {}", account.getId(), account.isPaper(), apiName,
					System.currentTimeMillis() - start, e.getMessage());
				throw e;
			} catch (RuntimeException e) {
				log.error("KIS 호출 중 예외 account={} api={} {}ms: {}", account.getId(), apiName,
					System.currentTimeMillis() - start, e.toString(), e);
				throw e;
			}
		}
	}
	public <T extends ApiResult> T execute(KisClient client, Api<T> api) {
		return check(client.execute(api));
	}

	private KisClient create(String appKey, String secretKey, String accountNumber, String productCode,
		boolean paper) {
		Credentials credentials = new Credentials(appKey, secretKey, accountNumber, productCode);
		// 모의투자 서버는 초당 호출 제한이 훨씬 낮다.
		credentials.setRestLimitPerSecond(KisLimits.perSecond(paper));
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
