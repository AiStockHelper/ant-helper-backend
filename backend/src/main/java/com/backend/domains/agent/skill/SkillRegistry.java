package com.backend.domains.agent.skill;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.backend.common.exception.ApiException;
import com.backend.common.exception.ErrorCode;

import lombok.extern.slf4j.Slf4j;

/** 등록된 스킬 빈 모음. 스킬을 추가하려면 {@link Skill} 구현체를 빈으로 만들면 된다. */
@Slf4j
@Component
public class SkillRegistry {

	private final Map<String, Skill> byId = new LinkedHashMap<>();

	public SkillRegistry(List<Skill> skills) {
		for (Skill s : skills) {
			if (byId.putIfAbsent(s.id(), s) != null) {
				throw new IllegalStateException("스킬 ID 가 중복됩니다: " + s.id());
			}
		}
		log.info("스킬 {}개 등록: {}", byId.size(), byId.keySet());
	}

	public List<Skill> all() {
		return List.copyOf(byId.values());
	}

	public Skill require(String id) {
		Skill s = id == null ? null : byId.get(id);
		if (s == null) {
			throw ApiException.from(ErrorCode.SKILL_NOT_FOUND);
		}
		return s;
	}

	public Skill find(String id) {
		return id == null ? null : byId.get(id);
	}
}
