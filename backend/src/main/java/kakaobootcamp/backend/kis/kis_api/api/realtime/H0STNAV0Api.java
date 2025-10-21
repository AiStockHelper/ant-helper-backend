package kakaobootcamp.backend.kis.kis_api.api.realtime;

import com.backend.kis.kis_api.api.CommonRealTimeApi;
import com.backend.kis.kis_client.api.annotation.RealTimeApi;

/**
 * 국내ETF NAV추이 [실시간-051] 요청
 */
@RealTimeApi(path = "/tryitout/H0STNAV0")
public class H0STNAV0Api extends CommonRealTimeApi<H0STNAV0Response> {

    public H0STNAV0Api(String trKey) {
        super("H0STNAV0", trKey);
    }

}
