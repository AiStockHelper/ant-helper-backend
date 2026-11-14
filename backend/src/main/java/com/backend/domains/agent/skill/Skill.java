package com.backend.domains.agent.skill;

import java.util.List;
import java.util.Map;

import com.backend.domains.agent.Limits;
import com.backend.domains.agent.Schedule;

/**
 * 스킬: 사용자가 '스킬' 메뉴에서 켜는 미리 만들어진 자동 실행 전략.
 * 새 스킬은 이 인터페이스를 구현한 Spring 빈으로 추가하면 목록·설정 화면·일정·실행이 자동으로 연결된다.
 */
public interface Skill {

	record Option(String value, String label) {
	}

	/**
	 * 설정 화면에 그려질 입력 항목.
	 * type: NUMBER | TEXT | SELECT | BOOLEAN
	 */
	record Param(
		String key, String label, String type, Object defaultValue, Double min, Double max, List<Option> options,
		String help, boolean required, String unit
	) {
		public static Param number(String key, String label, double def, double min, double max, String unit, String help) {
			return new Param(key, label, "NUMBER", def, min, max, null, help, true, unit);
		}

		public static Param select(String key, String label, String def, List<Option> options, String help) {
			return new Param(key, label, "SELECT", def, null, null, options, help, true, null);
		}

		public static Param text(String key, String label, String help) {
			return new Param(key, label, "TEXT", "", null, null, null, help, false, null);
		}
	}

	String id();

	String name();

	/** 목록에 보이는 한 줄 설명 */
	String summary();

	/** 상세 화면의 설명(마크다운). 동작 방식·위험·한계를 솔직하게 적는다. */
	String detail();

	String category();

	/** 이 스킬이 주문을 내는가(내면 실전 계좌에서 사용자 확인이 필요하다) */
	boolean trades();

	List<Param> params();

	/** 사용자 입력을 검증하고 기본값을 채운 설정을 돌려준다. 잘못되면 ApiException. */
	Map<String, Object> normalize(Map<String, Object> input);

	/**
	 * 이미 켜 둔 작업의 설정을 바꿀 때 스킬이 추가로 확인할 것(예: 보유 중에는 종목을 바꿀 수 없다).
	 * 바꾸면 안 되는 경우 ApiException 을 던진다. oldConfig 는 지금 저장된 값, newConfig 는 정규화된 새 값.
	 */
	default void validateChange(Map<String, Object> oldConfig, Map<String, Object> newConfig, boolean hasOpenPositions) {
	}

	Schedule schedule(Map<String, Object> config);

	/** 주문을 내는 스킬이 쓸 시드/한도. 주문하지 않으면 null. */
	Limits limits(Map<String, Object> config);

	String title(Map<String, Object> config);

	/** 일정이 되어 깨어났을 때 한 번 실행한다. 일어난 일은 ctx 에 남긴다. */
	void run(SkillContext ctx);
}
