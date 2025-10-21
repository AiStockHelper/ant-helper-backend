package kakaobootcamp.backend.kis.kis_api.api.realtime;

import com.backend.kis.kis_api.api.CommonRealTimeApi;
import com.backend.kis.kis_client.api.annotation.RealTimeApi;

/**
 * ELW 실시간예상체결 [실시간-063] 요청
 */
@RealTimeApi(path = "/tryitout/H0EWANC0")
public class H0EWANC0Api extends CommonRealTimeApi<H0EWANC0Response> {

    public H0EWANC0Api(String trKey) {
        super("H0EWANC0", trKey);
    }

}
