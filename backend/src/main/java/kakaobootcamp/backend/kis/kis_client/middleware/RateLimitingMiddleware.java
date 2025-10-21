package kakaobootcamp.backend.kis.kis_client.middleware;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.backend.kis.kis_client.KisClient;
import com.backend.kis.kis_client.api.special.RateLimitInfoResult;
import com.backend.kis.kis_client.client.http.HttpClientRequest;
import com.backend.kis.kis_client.config.Credentials;
import com.backend.kis.kis_client.context.ApiContext;
import com.backend.kis.kis_client.util.RateLimiter;

public class RateLimitingMiddleware implements Middleware {

    private final ConcurrentMap<Credentials, RateLimiter> rateLimiters = new ConcurrentHashMap<>();

    @Override
    public void afterInit(KisClient client, ApiContext context) {
        if (context.getApiData().getResponseClass() == RateLimitInfoResult.class) {
            Credentials credentials = context.getCredentials();
            RateLimiter limiter = rateLimiters.get(credentials);

            RateLimitInfoResult result = new RateLimitInfoResult();

            if (limiter == null) {
                result.setRemainingQuota(credentials.getRestLimitPerSecond());
            } else {
                result.setRemainingQuota(limiter.getRemainingQuota());
            }

            context.setApiResult(result);
        }
    }

    @Override
    public void before(KisClient client, ApiContext context) {
        if (!(context.getRequest() instanceof HttpClientRequest)) {
            return;
        }

        Credentials credentials = context.getCredentials();

        int limit = credentials.getRestLimitPerSecond();
        rateLimiters.computeIfAbsent(credentials, key -> new RateLimiter(limit));

        RateLimiter limiter = rateLimiters.get(credentials);

        limiter.acquire(); // wait
    }

    @Override
    public void after(KisClient client, ApiContext context) {

    }

}
