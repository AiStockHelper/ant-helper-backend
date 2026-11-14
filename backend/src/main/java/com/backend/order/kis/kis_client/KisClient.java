package com.backend.order.kis.kis_client;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.backend.order.kis.kis_client.api.Api;
import com.backend.order.kis.kis_client.api.ApiParser;
import com.backend.order.kis.kis_client.api.ApiResult;
import com.backend.order.kis.kis_client.client.NetworkClient;
import com.backend.order.kis.kis_client.client.NetworkRequest;
import com.backend.order.kis.kis_client.client.http.JavaHttpClient;
import com.backend.order.kis.kis_client.client.nothing.NothingClient;
import com.backend.order.kis.kis_client.client.socket.JsrSocketClient;
import com.backend.order.kis.kis_client.config.Configuration;
import com.backend.order.kis.kis_client.config.Credentials;
import com.backend.order.kis.kis_client.context.ApiContext;
import com.backend.order.kis.kis_client.context.ApiData;
import com.backend.order.kis.kis_client.exception.InvalidApiRequestException;
import com.backend.order.kis.kis_client.exception.KisClientException;
import com.backend.order.kis.kis_client.middleware.AuthMiddleware;
import com.backend.order.kis.kis_client.middleware.Middleware;
import com.backend.order.kis.kis_client.middleware.PageableResultMiddleware;
import com.backend.order.kis.kis_client.middleware.RateLimitingMiddleware;

import lombok.Getter;

public class KisClient {

    @Getter
    private Configuration config;

    private List<Middleware> middlewares = new ArrayList<>();

    NetworkClient[] clients;

    private static final Logger logger = LoggerFactory.getLogger(KisClient.class);

    public KisClient(Configuration config) {
        logger.info("{} initializing...", this.getClass().getSimpleName());

        setConfig(config);

        middlewares.add(new AuthMiddleware());
        middlewares.add(new RateLimitingMiddleware());
        middlewares.add(new PageableResultMiddleware());
        middlewares.addAll(config.getAllMiddlewares());

        clients = new NetworkClient[] {
            new JavaHttpClient(config),
            new JsrSocketClient(config),
            new NothingClient()
        };

        logger.info("{} initialized successfully.", this.getClass().getSimpleName());
    }

    private void setConfig(Configuration config) {
        if (!config.isMaskCredentials()) {
            logger.warn("Credential masking is disabled. Be careful not to expose your credentials in logs.");
        }

        this.config = config;
    }

    public <T extends ApiResult> T execute(Api<T> api) {
        Credentials credentials = config.getCredentials();

        return execute(api, credentials);
    }

    public <T extends ApiResult> T execute(Api<T> api, String credentialsName) {
        Credentials credentials = config.getCredentials(credentialsName);

        return execute(api, credentials);
    }

    public <T extends ApiResult> T execute(Api<T> api, Credentials credentials) {
        logger.trace("API Request begins [{}] {}", api.getClass().getSimpleName());

        ApiParser parser = new ApiParser(api);
        ApiData data = parser.parse();

        try {
            return execute(data, credentials);
        } catch (KisClientException e) {
            logger.error("API Request failed [{}] {}", api.getClass().getSimpleName(), e.getMessage());

            throw e;
        } catch (Exception e) {
            logger.error("API Request failed (Unknown Error) [{}] {}", api.getClass().getSimpleName(), e.getMessage());

            throw new KisClientException("Unknown error occurred.", e);
        }
    }

    /**
     * @hidden
     * Low level API request method.
     *
     * Do not use this method directly.
     */
    public <T extends ApiResult> T execute(ApiData data, Credentials credentials) {
        ApiContext context = new ApiContext(credentials);
        context.setApiData(data);

        for(Middleware middleware : this.middlewares) {
           middleware.afterInit(this, context);
        }

        NetworkClient client = this.getClient(data);
        NetworkRequest request = client.makeRequest(data);
        context.setNetworkClient(client);
        context.setRequest(request);

        for(Middleware middleware : this.middlewares) {
           middleware.before(this, context);
        }

        try {
            client.execute(context);
        } catch (IOException e) {
            throw new InvalidApiRequestException("Failed to get response from server. (" + e.getMessage() + ")", e);
        }

        for (Middleware middleware : middlewares) {
            middleware.after(this, context);
        }

        ApiResult result = context.getApiResult();

        @SuppressWarnings("unchecked")
        T _result = (T)result;

        return _result;
    }

    public void close() {
        for (NetworkClient client : clients) {
            try {
                client.close();
            } catch (IOException e) {
                logger.error("Failed to close client. {}", e.getMessage());
            }
        }
    }

    private NetworkClient getClient(ApiData data) {
        for (NetworkClient client : clients) {
            if (client.isSupport(data)) {
                return client;
            }
        }

        throw new InvalidApiRequestException("No client supports the request.");
    }

}
