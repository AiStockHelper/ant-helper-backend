package com.backend.common.swagger;

import java.util.Arrays;
import java.util.stream.Collectors;

import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerMethod;

import io.swagger.v3.oas.models.Operation;

@Configuration
public class EnumValueOperationCustomizer {

	@Bean
	public OperationCustomizer addEnumValuesToDescription() {
		return (Operation operation, HandlerMethod handlerMethod) -> {

			// 메서드 파라미터 순회
			Arrays.stream(handlerMethod.getMethodParameters())
				// @RequestBody DTO만 필터링
				.filter(param -> param.hasParameterAnnotation(org.springframework.web.bind.annotation.RequestBody.class))
				.forEach(param -> {
					Class<?> dtoClass = param.getParameterType();

					// DTO 내부 필드 중 Enum 타입만 필터링
					Arrays.stream(dtoClass.getDeclaredFields())
						.filter(field -> field.getType().isEnum())
						.forEach(field -> {
							Class<?> enumType = field.getType();

							// Enum 값 목록 추출
							String enumValues = Arrays.stream(enumType.getEnumConstants())
								.map(Object::toString)
								.collect(Collectors.joining(", "));

							// 기존 description 뒤에 추가
							String desc = operation.getDescription() == null ? "" : operation.getDescription() + " ";
							operation.setDescription(desc + String.format("<br> %s 가능한 값: [%s]", field.getName(), enumValues));
						});
				});

			return operation;
		};
	}
}