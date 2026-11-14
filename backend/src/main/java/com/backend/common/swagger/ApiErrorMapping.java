package com.backend.common.swagger;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.backend.common.exception.ErrorCode;

// ErrorCode의 내용을 Swagger API Response로 매핑하기 위한 어노테이션
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ApiErrorMapping {
	ErrorCode[] value();
}