package kakaobootcamp.backend.kis.kis_client.client.http;

import java.util.List;
import java.util.Map;

import com.backend.kis.kis_client.client.NetworkResponse;

import lombok.Builder;
import lombok.Data;

@Builder
@Data
public class HttpClientResponse implements NetworkResponse {

    private int statusCode;
    private Map<String, List<String>> headers;
    private String body;

}