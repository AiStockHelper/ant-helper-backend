package com.backend.order.kis.kis_api.api;

import com.backend.order.kis.kis_client.api.ApiResult;
import com.backend.order.kis.kis_client.api.annotation.Header;

import lombok.Getter;
import lombok.ToString;

@Getter
@ToString()
public abstract class CommonRestResult implements ApiResult {

    @Header("content-type")
    private String contentType;

    @Header
    private String trId;

    @Header
    private String trCont;

    @Header
    private String gtUid;

}
