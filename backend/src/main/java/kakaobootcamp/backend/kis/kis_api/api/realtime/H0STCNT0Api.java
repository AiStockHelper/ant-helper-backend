package kakaobootcamp.backend.kis.kis_api.api.realtime;

import com.backend.kis.kis_api.api.CommonRealTimeApi;
import com.backend.kis.kis_client.api.annotation.RealTimeApi;

/**
 * 국내주식 실시간체결가 (KRX) [실시간-003] 요청
 */
@RealTimeApi(path = "/tryitout/H0STCNT0")
public class H0STCNT0Api extends CommonRealTimeApi<H0STCNT0Response> {

    public H0STCNT0Api(String trKey) {
        super("H0STCNT0", trKey);
    }

}
