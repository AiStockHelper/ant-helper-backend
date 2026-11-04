package com.backend.common.util;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/** DB 의 JSON 컬럼과 도구 인자 변환용 얇은 래퍼. */
@Component
public class Json {

	private final ObjectMapper mapper;

	public Json(ObjectMapper mapper) {
		this.mapper = mapper;
	}

	public String write(Object value) {
		if (value == null) {
			return null;
		}
		try {
			return mapper.writeValueAsString(value);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("JSON 직렬화 실패", e);
		}
	}

	public <T> T read(String json, TypeReference<T> type, T fallback) {
		if (json == null || json.isBlank()) {
			return fallback;
		}
		try {
			return mapper.readValue(json, type);
		} catch (JsonProcessingException e) {
			return fallback;
		}
	}

	public <T> T read(String json, Class<T> type) {
		if (json == null || json.isBlank()) {
			return null;
		}
		try {
			return mapper.readValue(json, type);
		} catch (JsonProcessingException e) {
			return null;
		}
	}

	public ObjectMapper mapper() {
		return mapper;
	}
}
