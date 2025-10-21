package kakaobootcamp.backend.kis.kis_client.api.rest.auth;


import com.backend.kis.kis_client.api.ApiResult;
import com.backend.kis.kis_client.api.annotation.Header;

import lombok.Getter;

@Getter
public class GetSocketApprovalKeyResult implements ApiResult {

    @Header("content-type")
    private String contentType;

    @Header
    private String trId;

    @Header
    private String trCont;

    @Header
    private String gtUid;

    private String approvalKey;

}