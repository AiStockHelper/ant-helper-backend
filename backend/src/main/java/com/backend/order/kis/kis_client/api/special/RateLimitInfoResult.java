package com.backend.order.kis.kis_client.api.special;

import com.backend.order.kis.kis_client.api.ApiResult;

import lombok.Data;

@Data
public class RateLimitInfoResult implements ApiResult {

    int remainingQuota;

}
