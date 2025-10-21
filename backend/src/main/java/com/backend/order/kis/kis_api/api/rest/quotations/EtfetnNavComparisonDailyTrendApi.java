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
 * NAV 비교추이(일)[v1_국내주식-071]
 */
@NoArgsConstructor
@RequiredArgsConstructor
@Setter
@RestApi(method = RestApi.Method.GET, path = "/uapi/etfetn/v1/quotations/nav-comparison-daily-trend")
public class EtfetnNavComparisonDailyTrendApi extends CommonRestApi<EtfetnNavComparisonDailyTrendResult> {

    @Header
    private String trId = "FHPST02440200";

    /**
     * FID 조건 시장 분류 코드
     *
     * J 입력
     */
    @Parameter
    private String fidCondMrktDivCode = "J";

    /**
     * FID 입력 종목코드
     *
     * 종목코드 (6자리)
     */
    @Parameter
    @NonNull
    private String fidInputIscd;

    /**
     * FID 입력 날짜1
     *
     * 조회 시작일자 (ex. 20240101)
     */
    @Parameter("FID_INPUT_DATE_1")
    @NonNull
    private String fidInputDate1;

    /**
     * FID 입력 날짜2
     *
     * 조회 종료일자 (ex. 20240220)
     */
    @Parameter("FID_INPUT_DATE_2")
    @NonNull
    private String fidInputDate2;

}
