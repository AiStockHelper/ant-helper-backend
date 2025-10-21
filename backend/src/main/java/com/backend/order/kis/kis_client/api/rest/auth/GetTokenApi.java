package com.backend.order.kis.kis_client.api.rest.auth;

import com.backend.order.kis.kis_client.api.Api;
import com.backend.order.kis.kis_client.api.annotation.Body;
import com.backend.order.kis.kis_client.api.annotation.RestApi;
import com.backend.order.kis.kis_client.api.annotation.auth.AppKeyRequired;
import com.backend.order.kis.kis_client.api.annotation.auth.AppSecretRequired;

import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

@NoArgsConstructor
@AllArgsConstructor
@RestApi(method = RestApi.Method.POST, path = "/oauth2/tokenP")
@AppKeyRequired(location = AppKeyRequired.Location.BODY)
@AppSecretRequired(location = AppSecretRequired.Location.BODY)
public class GetTokenApi implements Api<GetTokenResult> {

    @Body("grant_type")
    private String grantType = "client_credentials";

}