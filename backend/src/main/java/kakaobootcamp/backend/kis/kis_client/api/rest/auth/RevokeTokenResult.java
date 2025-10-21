package kakaobootcamp.backend.kis.kis_client.api.rest.auth;

import com.backend.kis.kis_client.api.ApiResult;

import lombok.Getter;

@Getter
public class RevokeTokenResult implements ApiResult {

    private String code;
    private String message;

}
