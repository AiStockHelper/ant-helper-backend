package com.backend.market;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 체결 내역으로 매도별 실현손익을 계산한다(이동평균법).
 * 종목마다 시간순으로 매수는 평균 매입단가를 갱신하고, 매도는 (매도가 − 그때까지의 평균 매입단가) × 수량을 실현손익으로 본다.
 * 조회한 기간 안에 매수 기록이 없는 수량을 파는 경우는 원가를 알 수 없으므로 계산하지 않는다(결과에 없음).
 * 수수료·세금은 포함하지 않는다.
 */
public final class TradeProfit {

	private TradeProfit() {
	}

	/** key 는 종목을 구분하는 값(예: 시장+코드). index 는 입력 목록에서의 위치로, 결과를 되돌려 줄 때 쓴다. */
	public record Fill(int index, String key, boolean sell, long quantity, BigDecimal price, Instant at) {
	}

	/** costPrice: 매도 시점의 평균 매입단가, pnl: 실현손익 금액, pnlRate: 손익률(%) */
	public record Realized(BigDecimal costPrice, BigDecimal pnl, double pnlRate) {
	}

	private static final class Position {
		long quantity;
		BigDecimal avg = BigDecimal.ZERO;
	}

	/** @return 원가를 알 수 있는 매도 체결의 index → 실현손익 */
	public static Map<Integer, Realized> realized(List<Fill> fills) {
		List<Fill> ordered = new ArrayList<>(fills);
		// 같은 시각이면 매수를 먼저 본다(같은 분 안에 사고판 경우를 자연스럽게 처리)
		ordered.sort(Comparator.comparing(Fill::at).thenComparing(Fill::sell).thenComparingInt(Fill::index));
		Map<String, Position> positions = new HashMap<>();
		Map<Integer, Realized> out = new HashMap<>();
		for (Fill f : ordered) {
			if (f.quantity() <= 0 || f.price() == null || f.price().signum() <= 0) {
				continue;
			}
			Position p = positions.computeIfAbsent(f.key(), k -> new Position());
			if (!f.sell()) {
				BigDecimal total = p.avg.multiply(BigDecimal.valueOf(p.quantity)).add(f.price().multiply(BigDecimal.valueOf(f.quantity())));
				p.quantity += f.quantity();
				p.avg = total.divide(BigDecimal.valueOf(p.quantity), 6, RoundingMode.HALF_UP);
				continue;
			}
			if (p.quantity >= f.quantity() && p.avg.signum() > 0) {
				BigDecimal diff = f.price().subtract(p.avg);
				BigDecimal pnl = diff.multiply(BigDecimal.valueOf(f.quantity()));
				double rate = diff.divide(p.avg, 6, RoundingMode.HALF_UP).doubleValue() * 100;
				out.put(f.index(), new Realized(p.avg.setScale(4, RoundingMode.HALF_UP), pnl.setScale(4, RoundingMode.HALF_UP),
					Math.round(rate * 100) / 100.0));
				p.quantity -= f.quantity();
				if (p.quantity == 0) {
					p.avg = BigDecimal.ZERO;
				}
			} else {
				p.quantity = 0;                    // 알 수 없는 원가의 수량을 팔았다: 이후 계산이 어긋나지 않게 비운다
				p.avg = BigDecimal.ZERO;
			}
		}
		return out;
	}
}
