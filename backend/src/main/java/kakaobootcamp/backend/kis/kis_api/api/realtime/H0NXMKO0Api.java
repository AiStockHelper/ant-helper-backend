package kakaobootcamp.backend.kis.kis_api.api.realtime;

import com.backend.kis.kis_api.api.CommonRealTimeApi;
import com.backend.kis.kis_client.api.annotation.RealTimeApi;

/**
 * 국내주식 장운영정보 (NXT) 요청
 */
@RealTimeApi(path = "/tryitout/H0NXMKO0")
public class H0NXMKO0Api extends CommonRealTimeApi<H0NXMKO0Response> {

    public H0NXMKO0Api(String trKey) {
        super("H0NXMKO0", trKey);
    }

}
