package kakaobootcamp.backend.kis.kis_api.api.realtime;

import com.backend.kis.kis_api.api.CommonRealTimeApi;
import com.backend.kis.kis_client.api.annotation.RealTimeApi;

/**
 * 국내주식 실시간체결가 (NXT) 요청
 */
@RealTimeApi(path = "/tryitout/H0NXCNT0")
public class H0NXCNT0Api extends CommonRealTimeApi<H0NXCNT0Response> {

    public H0NXCNT0Api(String trKey) {
        super("H0NXCNT0", trKey);
    }

}
