package kakaobootcamp.backend.kis.kis_client.api.special;

import com.backend.kis.kis_client.api.ApiResult;

import lombok.Data;

@Data
public class RateLimitInfoResult implements ApiResult {

    int remainingQuota;

}
