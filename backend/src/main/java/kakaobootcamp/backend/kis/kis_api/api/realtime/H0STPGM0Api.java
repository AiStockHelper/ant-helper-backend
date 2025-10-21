package kakaobootcamp.backend.kis.kis_api.api.realtime;

import com.backend.kis.kis_api.api.CommonRealTimeApi;
import com.backend.kis.kis_client.api.annotation.RealTimeApi;

/**
 * 국내주식 실시간프로그램매매 (KRX) [실시간-048] 요청
 */
@RealTimeApi(path = "/tryitout/H0STPGM0")
public class H0STPGM0Api extends CommonRealTimeApi<H0STPGM0Response> {

    public H0STPGM0Api(String trKey) {
        super("H0STPGM0", trKey);
    }

}
