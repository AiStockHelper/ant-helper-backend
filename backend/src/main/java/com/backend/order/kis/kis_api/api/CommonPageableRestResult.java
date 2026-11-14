package com.backend.order.kis.kis_api.api;

import com.backend.order.kis.kis_client.api.ApiResult;
import com.backend.order.kis.kis_client.api.annotation.Header;
import com.backend.order.kis.kis_client.api.rest.PageableApiResult;

import lombok.Getter;
import lombok.ToString;

@Getter
@ToString()
public abstract class CommonPageableRestResult<T extends ApiResult> extends PageableApiResult<T> {

    @Header("content-type")
    private String contentType;

    @Header
    private String trId;

    @Header
    private String gtUid;

}
