package com.backend.kis.kis_client.client;

import java.io.IOException;

import com.backend.kis.kis_client.context.ApiContext;
import com.backend.kis.kis_client.context.ApiData;

public interface NetworkClient {

    public boolean isSupport(ApiData apiData);

    public NetworkRequest makeRequest(ApiData apiData);
    public void execute(ApiContext context) throws IOException;

    public void close() throws IOException;

}
