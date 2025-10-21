package com.backend.order.kis.kis_api.api;

import com.backend.order.kis.kis_client.api.RealTimeApiData;
import com.backend.order.kis.kis_client.client.socket.SubscribableApiResult;

public abstract class CommonRealTimeResult<T extends RealTimeApiData> extends SubscribableApiResult<T> {

}
