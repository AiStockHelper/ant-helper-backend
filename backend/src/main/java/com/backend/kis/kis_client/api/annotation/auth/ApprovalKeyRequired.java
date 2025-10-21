package com.backend.kis.kis_client.api.annotation.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ApprovalKeyRequired {
    public enum Location {
        HEADER,
        BODY,
    }

    String key() default "approval_key";
    Location location() default Location.HEADER;

}
