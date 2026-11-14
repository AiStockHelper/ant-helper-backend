package com.backend.order.kis.kis_api.api;

import com.backend.order.kis.kis_client.api.Api;
import com.backend.order.kis.kis_client.api.ApiResult;
import com.backend.order.kis.kis_client.api.annotation.Body;
import com.backend.order.kis.kis_client.api.annotation.Header;
import com.backend.order.kis.kis_client.api.annotation.auth.ApprovalKeyRequired;
import com.backend.order.kis.kis_client.api.realtime.TransactionType;

import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;

@Getter
@Setter
@ApprovalKeyRequired(location = ApprovalKeyRequired.Location.HEADER)
public abstract class CommonRealTimeApi<T extends ApiResult> implements Api<T> {

    public CommonRealTimeApi(String trId, String trKey) {
        this.trId = trId;
        this.trKey = trKey;
    }

    @Header
    private String custtype = "P";

    @Header
    private TransactionType trType = TransactionType.SUBSCRIBE;

    @Header("content-type")
    private String contentType = "utf-8";

    @NonNull
    @Body("tr_id")
    private String trId;

    @NonNull
    @Body("tr_key")
    private String trKey;

}
