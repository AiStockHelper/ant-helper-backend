package com.backend.order.kis.kis_client.client.socket;

import java.util.HashMap;
import java.util.Map;

import com.backend.order.kis.kis_client.client.NetworkRequest;
import com.backend.order.kis.kis_client.util.JsonUtil;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class WebSocketRequest implements NetworkRequest, Cloneable {

    private String url;
    private Map<String, Object> headers;
    private Map<String, Object> body;

    protected String toJson() {
        Map<String, Object> map = Map.of(
            "header", headers,
            "body", Map.of("input", body)
        );

        return JsonUtil.toJson(map);
    }

    protected WebSocketRequest clone() {
        return WebSocketRequest.builder()
            .url(url)
            .headers(new HashMap<>(headers))
            .body(new HashMap<>(body))
            .build();
    }

}
