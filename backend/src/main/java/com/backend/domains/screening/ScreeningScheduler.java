package com.backend.domains.screening;

import java.time.LocalDate;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.backend.common.properties.AppProperties;
import com.backend.market.Market;
import com.backend.market.TradingCalendar;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 장이 끝난 뒤 시장별로 스크리닝을 돌린다. 시간대를 지정해 서머타임을 따라가고 휴장일은 건너뛴다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScreeningScheduler {

	private final ScreeningService screening;
	private final TradingCalendar calendar;
	private final AppProperties props;

	/** 한국: 평일 17:10 KST (종가·거래량 확정 후) */
	@Scheduled(cron = "0 10 17 * * MON-FRI", zone = "Asia/Seoul")
	public void krx() {
		launch(ScreeningService.KRX, Market.KRX);
	}

	/** 미국: 평일 17:30 뉴욕 현지(정규장 마감 90분 뒤). 한국 시간으로는 아침 06~07시대 */
	@Scheduled(cron = "0 30 17 * * MON-FRI", zone = "America/New_York")
	public void us() {
		launch(ScreeningService.US, Market.NASDAQ);
	}

	private void launch(String group, Market zone) {
		if (!props.getScreening().isEnabled()) {
			return;
		}
		if (!calendar.isTradingDay(zone, LocalDate.now(zone.zone()))) {
			log.info("[{}] 오늘은 휴장일이라 스크리닝을 건너뜁니다.", group);
			return;
		}
		screening.runAsync(group);
	}

	@EventListener(ApplicationReadyEvent.class)
	public void onReady() {
		if (!props.getScreening().isEnabled() || !props.getScreening().isRunOnStartupIfEmpty()) {
			return;
		}
		Thread.ofVirtual().name("screening-startup").start(() -> {
			try {
				Thread.sleep(60_000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
			for (String g : new String[] {ScreeningService.KRX, ScreeningService.US}) {
				if (!screening.hasAny(g)) {
					screening.runBlocking(g);
				}
			}
		});
	}
}
