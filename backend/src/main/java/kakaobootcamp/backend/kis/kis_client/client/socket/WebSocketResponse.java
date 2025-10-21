package kakaobootcamp.backend.kis.kis_client.client.socket;

import java.util.Map;

import com.backend.kis.kis_client.client.NetworkResponse;

import lombok.Data;

@Data
public class WebSocketResponse implements NetworkResponse {

    private Map<String, String> header;
    private Body body;

    @Data
    public class Body {
        private String rtCd;
        private String msgCd;
        private String msg1;
        private Map<String, String> output;
    }

    protected String getHeader(String key) {
        return header.get(key);
    }


}