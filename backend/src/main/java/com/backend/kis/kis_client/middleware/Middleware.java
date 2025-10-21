package com.backend.kis.kis_client.middleware;

import com.backend.kis.kis_client.KisClient;
import com.backend.kis.kis_client.context.ApiContext;

public interface Middleware {

    /**
     * ApiData 생성 후 실행되는 메서드
     *
     * @param client Client 객체
     * @param context API 요청에 대한 정보를 담은 객체 (apiData 제외 모든 필드 null)
     */
    public void afterInit(KisClient client, ApiContext context);

    /**
     * API 요청 전 실행되는 메서드
     *
     * @param client Client 객체
     * @param context API 요청에 대한 정보를 담은 객체 (response, apiResult는 null)
     */
    public void before(KisClient client, ApiContext context);

    /**
     * API 응답 후 실행되는 메서드
     *
     * @param client Client 객체
     * @param context API 요청에 대한 정보를 담은 객체
     */
    public void after(KisClient client, ApiContext context);

}
