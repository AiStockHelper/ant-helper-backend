package kakaobootcamp.backend.kis.kis_api.api.realtime;

import com.backend.kis.kis_api.api.CommonRealTimeApi;
import com.backend.kis.kis_client.api.annotation.RealTimeApi;

/**
 * 국내주식 실시간프로그램매매 (NXT) 요청
 */
@RealTimeApi(path = "/tryitout/H0NXPGM0")
public class H0NXPGM0Api extends CommonRealTimeApi<H0NXPGM0Response> {

    public H0NXPGM0Api(String trKey) {
        super("H0NXPGM0", trKey);
    }

}
