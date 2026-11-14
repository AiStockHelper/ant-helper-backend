package com.backend.order.kis.kis_api.api.rest.overseasTrading;

import com.backend.order.kis.kis_api.api.CommonRestApi;
import com.backend.order.kis.kis_client.api.annotation.Body;
import com.backend.order.kis.kis_client.api.annotation.Header;
import com.backend.order.kis.kis_client.api.annotation.RestApi;
import com.backend.order.kis.kis_client.api.annotation.auth.AccountRequired;

import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

/**
 * 해외주식 주문(현금)[해외주식-001]
 */
@NoArgsConstructor
@RequiredArgsConstructor
@Setter
@AccountRequired
@RestApi(method = RestApi.Method.POST, path = "/uapi/overseas-stock/v1/trading/order")
public class OverseasOrderCashApi extends CommonRestApi<OverseasOrderCashResult> {

    /**
     * 거래ID
     * 
     * [실전투자]
     * TTTT1002U : 미국 매수 주문
     * TTTT1006U : 미국 매도 주문
     * TTTS0308U : 일본 매수 주문
     * TTTS0307U : 일본 매도 주문
     * TTTS0202U : 상해 매수 주문
     * TTTS1005U : 상해 매도 주문
     * TTTS1002U : 홍콩 매수 주문
     * TTTS1001U : 홍콩 매도 주문
     * TTTS0305U : 심천 매수 주문
     * TTTS0304U : 심천 매도 주문
     * TTTS0311U : 베트남 매수 주문
     * TTTS0310U : 베트남 매도 주문
     * 
     * [모의투자]
     * VTTT1002U : 미국 매수 주문
     * VTTT1001U : 미국 매도 주문
     * VTTS0308U : 일본 매수 주문
     * VTTS0307U : 일본 매도 주문
     * VTTS0202U : 상해 매수 주문
     * VTTS1005U : 상해 매도 주문
     * VTTS1002U : 홍콩 매수 주문
     * VTTS1001U : 홍콩 매도 주문
     * VTTS0305U : 심천 매수 주문
     * VTTS0304U : 심천 매도 주문
     * VTTS0311U : 베트남 매수 주문
     * VTTS0310U : 베트남 매도 주문
     */
    @Header
    private String trId = "VTTT1002U";

    /**
     * 연속거래여부
     * 
     * 공란 : 해당없음
     */
    @Header
    private String trCont = "";

    /**
     * 고객타입
     * 
     * B : 법인
     * P : 개인
     */
    @Header
    private String custtype = "P";

    /**
     * 상품번호
     * 
     * 종목코드 (해외 종목코드)
     */
    @Body
    @NonNull
    private String pdno;

    /**
     * 주문수량
     * 
     * 주문수량 (해외거래소 별 최소 주문수량 및 주문단위 확인 필요)
     */
    @Body
    @NonNull
    private String ordQty;

    /**
     * 해외주문단가
     * 
     * 1주당 가격
     * 시장가의 경우 "0"으로 입력
     */
    @Body
    @NonNull
    private String ovrsOrdUnpr;

    /**
     * 주문서버구분코드
     * 
     * "0" (Default)
     */
    @Body
    private String ordSvrDvsnCd = "0";

    /**
     * 주문구분
     * 
     * [미국 매수 주문 TTTT1002U]
     * 00 : 지정가
     * 32 : LOO(장개시지정가)
     * 34 : LOC(장마감지정가)
     * 35 : TWAP (시간가중평균)
     * 36 : VWAP (거래량가중평균)
     * 
     * [미국 매도 주문 TTTT1006U]
     * 00 : 지정가
     * 31 : MOO(장개시시장가)
     * 32 : LOO(장개시지정가)
     * 33 : MOC(장마감시장가)
     * 34 : LOC(장마감지정가)
     * 35 : TWAP (시간가중평균)
     * 36 : VWAP (거래량가중평균)
     * 
     * 모의투자의 경우 00:지정가만 가능
     */
    @Body
    private String ordDvsn = "00";

}