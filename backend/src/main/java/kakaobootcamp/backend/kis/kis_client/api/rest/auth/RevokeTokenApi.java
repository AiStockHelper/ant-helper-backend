package kakaobootcamp.backend.kis.kis_client.api.rest.auth;

import com.backend.kis.kis_client.api.Api;
import com.backend.kis.kis_client.api.annotation.RestApi;
import com.backend.kis.kis_client.api.annotation.auth.AppKeyRequired;
import com.backend.kis.kis_client.api.annotation.auth.AppSecretRequired;
import com.backend.kis.kis_client.api.annotation.auth.AppTokenRequired;

import lombok.NoArgsConstructor;

@NoArgsConstructor
@RestApi(method = RestApi.Method.POST, path = "/oauth2/revokeP")
@AppKeyRequired(location = AppKeyRequired.Location.BODY)
@AppSecretRequired(location = AppSecretRequired.Location.BODY)
@AppTokenRequired(location = AppTokenRequired.Location.BODY, key = "token")
public class RevokeTokenApi implements Api<RevokeTokenResult> {

}
