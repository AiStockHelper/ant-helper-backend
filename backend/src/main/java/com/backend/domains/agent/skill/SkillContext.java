package com.backend.domains.agent.skill;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.backend.domains.agent.AgentTask;
import com.backend.domains.agent.AgentTradingService;
import com.backend.domains.member.domain.MemberAccount;
import com.backend.domains.trade.TradePlanner;
import com.backend.market.MarketDataService;
import com.backend.market.PredictionService;
import com.backend.market.TradingCalendar;
import com.backend.market.dto.MarketDtos.OrderOutcome;

/** 스킬이 한 번 실행되는 동안 쓰는 도구 모음. 스킬은 이 객체로만 시세 조회·주문을 한다. */
public final class SkillContext {

	public record Services(AgentTradingService trading, MarketDataService marketData, PredictionService prediction,
						   TradePlanner planner, TradingCalendar calendar) {
	}

	private final AgentTask task;
	private final MemberAccount account;
	private final Map<String, Object> config;
	private final Map<String, Object> state;
	private final boolean manual;
	private final Services services;
	private final List<String> events = new ArrayList<>();
	private final List<OrderOutcome> outcomes = new ArrayList<>();
	private String pauseReason;

	public SkillContext(AgentTask task, MemberAccount account, Map<String, Object> config, Map<String, Object> state,
		boolean manual, Services services) {
		this.task = task;
		this.account = account;
		this.config = config;
		this.state = state;
		this.manual = manual;
		this.services = services;
	}

	public AgentTask task() {
		return task;
	}

	public MemberAccount account() {
		return account;
	}

	public boolean manual() {
		return manual;
	}

	public Services services() {
		return services;
	}

	/** 실행 사이에 유지되는 상태. 꺼내 쓰고 바꾸면 실행이 끝날 때 저장된다. */
	public Map<String, Object> state() {
		return state;
	}

	public double num(String key, double def) {
		Object v = config.get(key);
		if (v instanceof Number n) {
			return n.doubleValue();
		}
		try {
			return v == null ? def : Double.parseDouble(v.toString());
		} catch (NumberFormatException e) {
			return def;
		}
	}

	public String str(String key, String def) {
		Object v = config.get(key);
		return v == null || v.toString().isBlank() ? def : v.toString();
	}

	public String stateStr(String key) {
		Object v = state.get(key);
		return v == null ? null : v.toString();
	}

	/** 사용자에게 보여줄 한 줄 기록 */
	public void event(String text) {
		events.add(text);
	}

	public void outcome(OrderOutcome o) {
		outcomes.add(o);
	}

	public List<String> events() {
		return Collections.unmodifiableList(events);
	}

	public List<OrderOutcome> outcomes() {
		return Collections.unmodifiableList(outcomes);
	}

	/** 스킬이 스스로 작업을 멈추게 한다(예: 손실 한도 도달). */
	public void requestPause(String reason) {
		this.pauseReason = reason;
	}

	public String pauseReason() {
		return pauseReason;
	}
}
