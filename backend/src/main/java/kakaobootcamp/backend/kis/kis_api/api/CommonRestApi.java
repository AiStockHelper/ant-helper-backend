package kakaobootcamp.backend.kis.kis_api.api;

import com.backend.kis.kis_client.api.Api;
import com.backend.kis.kis_client.api.ApiResult;
import com.backend.kis.kis_client.api.annotation.Header;
import com.backend.kis.kis_client.api.annotation.auth.AppKeyRequired;
import com.backend.kis.kis_client.api.annotation.auth.AppSecretRequired;
import com.backend.kis.kis_client.api.annotation.auth.AppTokenRequired;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@AppKeyRequired(location = AppKeyRequired.Location.HEADER)
@AppSecretRequired(location = AppSecretRequired.Location.HEADER)
@AppTokenRequired(location = AppTokenRequired.Location.HEADER)
public abstract class CommonRestApi<T extends ApiResult> implements Api<T> {

    @Header("content-type")
    private String contentType = "application/json; charset=utf-8";

    @Header
    private String personalseckey;

    @Header
    private String trCont;

    @Header
    private String custtype = "P";

    @Header
    private String seqNo;

    @Header
    private String macAddress;

    @Header
    private String phoneNumber;

    @Header
    private String ipAddr;

    @Header
    private String gtUid;

}
