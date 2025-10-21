package kakaobootcamp.backend.kis.kis_api.api.realtime;

import com.backend.kis.kis_api.api.CommonRealTimeApi;
import com.backend.kis.kis_client.api.annotation.RealTimeApi;

/**
 * 국내지수 실시간체결 [실시간-026] 요청
 */
@RealTimeApi(path = "/tryitout/H0UPCNT0")
public class H0UPCNT0Api extends CommonRealTimeApi<H0UPCNT0Response> {

    public H0UPCNT0Api(String trKey) {
        super("H0UPCNT0", trKey);
    }

}
