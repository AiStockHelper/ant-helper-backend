package kakaobootcamp.backend.kis.kis_api.api.realtime;

import com.backend.kis.kis_api.api.CommonRealTimeApi;
import com.backend.kis.kis_client.api.annotation.RealTimeApi;

/**
 * 국내주식 실시간체결통보 [실시간-005] 요청
 */
@RealTimeApi(path = "/tryitout/H0STCNI0")
public class H0STCNI0Api extends CommonRealTimeApi<H0STCNI0Response> {

    public H0STCNI0Api(String trKey) {
        super("H0STCNI0", trKey);
    }

}
