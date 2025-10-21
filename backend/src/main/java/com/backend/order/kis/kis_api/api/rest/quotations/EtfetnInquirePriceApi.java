package com.backend.order.kis.kis_api.api.rest.quotations;

import com.backend.order.kis.kis_api.api.CommonRestApi;
import com.backend.order.kis.kis_client.api.annotation.Header;
import com.backend.order.kis.kis_client.api.annotation.Parameter;
import com.backend.order.kis.kis_client.api.annotation.RestApi;

import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

/**
 * ETF/ETN 현재가[v1_국내주식-068]
 */
@NoArgsConstructor
@RequiredArgsConstructor
@Setter
@RestApi(method = RestApi.Method.GET, path = "/uapi/etfetn/v1/quotations/inquire-price")
public class EtfetnInquirePriceApi extends CommonRestApi<EtfetnInquirePriceResult> {

    @Header
    private String trId = "FHPST02400000";

    /**
     * FID 입력 종목코드
     *
     * 종목코드
     */
    @Parameter
    @NonNull
    private String fidInputIscd;

    /**
     * FID 조건 시장 분류 코드
     *
     * J
     */
    @Parameter
    private String fidCondMrktDivCode = "J";

}
