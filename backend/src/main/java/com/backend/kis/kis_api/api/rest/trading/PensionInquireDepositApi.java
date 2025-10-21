package com.backend.kis.kis_api.api.rest.trading;

import com.backend.kis.kis_api.api.CommonRestApi;
import com.backend.kis.kis_client.api.annotation.Header;
import com.backend.kis.kis_client.api.annotation.Parameter;
import com.backend.kis.kis_client.api.annotation.RestApi;
import com.backend.kis.kis_client.api.annotation.auth.AccountRequired;

import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 퇴직연금 예수금조회[v1_국내주식-035]
 */
@NoArgsConstructor
@Setter
@AccountRequired(location= AccountRequired.Location.PARAMETER)
@RestApi(method = RestApi.Method.GET, path = "/uapi/domestic-stock/v1/trading/pension/inquire-deposit")
public class PensionInquireDepositApi extends CommonRestApi<PensionInquireDepositResult> {

    @Header
    private String trId = "TTTC0506R";

    /**
     * 적립금구분코드
     *
     * 00
     */
    @Parameter
    private String accaDvsnCd = "00";

}
