package kakaobootcamp.backend.kis.kis_api.api;

import com.backend.kis.kis_client.api.RealTimeApiData;
import com.backend.kis.kis_client.client.socket.SubscribableApiResult;

public abstract class CommonRealTimeResult<T extends RealTimeApiData> extends SubscribableApiResult<T> {

}
