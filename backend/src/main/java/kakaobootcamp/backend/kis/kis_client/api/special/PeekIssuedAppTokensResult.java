package kakaobootcamp.backend.kis.kis_client.api.special;

import java.time.LocalDateTime;
import java.util.Map;

import com.backend.kis.kis_client.api.ApiResult;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;

@Data
public class PeekIssuedAppTokensResult implements ApiResult {

    private Map<String, AppToken> appTokens;

    @Getter
    @AllArgsConstructor
    public static class AppToken {

        String appToken;
        LocalDateTime appTokenExpiredAt;

    }

}
