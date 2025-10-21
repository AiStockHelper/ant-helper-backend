package com.backend.kis.kis_api.api.rest.trading;

import com.backend.kis.kis_api.api.CommonRestApi;
import com.backend.kis.kis_client.api.annotation.Header;
import com.backend.kis.kis_client.api.annotation.Parameter;
import com.backend.kis.kis_client.api.annotation.RestApi;
import com.backend.kis.kis_client.api.annotation.auth.AccountRequired;

import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

/**
 * 매도가능수량조회 [국내주식-165]
 */
@NoArgsConstructor
@RequiredArgsConstructor
@Setter
@AccountRequired(location= AccountRequired.Location.PARAMETER)
@RestApi(method = RestApi.Method.GET, path = "/uapi/domestic-stock/v1/trading/inquire-psbl-sell")
public class InquirePsblSellApi extends CommonRestApi<InquirePsblSellResult> {

    @Header
    private String trId = "TTTC8408R";

    /**
     * 종목번호
     */
    @Parameter
    @NonNull
    private String pdno;

}
