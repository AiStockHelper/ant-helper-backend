package kakaobootcamp.backend.kis.kis_client.api.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface RestApi {
    public enum Method {
        GET,
        POST,
    }

    Method method();
    String path();
}
