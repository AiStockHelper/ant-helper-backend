package com.backend.common.swagger;

import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerMethod;

import com.backend.common.exception.ErrorCode;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;

@Configuration
public class ApiErrorMappingCustomizer {

	private static final String ERROR_EXAMPLE_JSON = """
		{
		  "isSuccess": false,
		  "status": "%s",
		  "code": "%s",
		  "message": "%s",
		  "reasons": {}
		}
		""";

	@Bean
	public OperationCustomizer applyApiErrorMapping() {
		return (Operation operation, HandlerMethod handlerMethod) -> {
			// ApiErrorMapping 애노테이션이 있는지 확인
			ApiErrorMapping mapping = handlerMethod.getMethodAnnotation(ApiErrorMapping.class);

			// 애노테이션이 있으면 해당 에러 코드를 OpenAPI 응답에 추가
			if (mapping != null) {
				ApiResponses responses = operation.getResponses();

				// operation에 ApiResponse 추가
				for (ErrorCode code : mapping.value()) {
					ApiResponse apiResponse = new ApiResponse()
						.description(code.getMessage())
						.content(new Content().addMediaType(
							"application/json",
							new MediaType()
								.schema(new Schema<>().$ref("#/components/schemas/ErrorResponse"))
								.example(String.format(ERROR_EXAMPLE_JSON,code.getHttpStatus().value(), code.getCode(), code.getMessage()))
						));

					responses.addApiResponse(
						String.valueOf(code.getHttpStatus().value()), // HTTP 상태 코드
						apiResponse // ApiResponse 객체
					);
				}
			}
			return operation;
		};
	}
}