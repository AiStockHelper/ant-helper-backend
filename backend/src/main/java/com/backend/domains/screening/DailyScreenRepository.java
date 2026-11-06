package com.backend.domains.screening;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DailyScreenRepository extends JpaRepository<DailyScreen, Long> {

	@Query("select max(d.tradeDate) from DailyScreen d where d.marketGroup = :group")
	Optional<LocalDate> latestTradeDate(@Param("group") String group);

	List<DailyScreen> findByMarketGroupAndTradeDateOrderByScoreDesc(String marketGroup, LocalDate tradeDate, Pageable pageable);

	/** 단기 모델이 상승을 예상한 종목(자동매매 스킬이 쓴다). 순서는 후보 점수순. */
	@Query("select d from DailyScreen d where d.marketGroup = :group and d.tradeDate = :date and d.direction = 'UP' "
		+ "and d.predictedChangeRate > 0 order by d.score desc")
	List<DailyScreen> findRisers(@Param("group") String group, @Param("date") LocalDate date, Pageable pageable);

	/** 요인 점수가 평균보다 확실히 높은 추천 후보(모델 추천). */
	@Query("select d from DailyScreen d where d.marketGroup = :group and d.tradeDate = :date and d.score > :minScore "
		+ "order by d.score desc")
	List<DailyScreen> findPicks(@Param("group") String group, @Param("date") LocalDate date, @Param("minScore") double minScore,
		Pageable pageable);

	/** 거래대금이 큰 순서(AI 가 직접 훑어보는 시장 개요용). */
	List<DailyScreen> findByMarketGroupAndTradeDateOrderByAvgTradeValueDesc(String marketGroup, LocalDate tradeDate, Pageable pageable);

	Optional<DailyScreen> findByMarketGroupAndTradeDateAndSymbol(String marketGroup, LocalDate tradeDate, String symbol);

	long countByMarketGroupAndTradeDate(String marketGroup, LocalDate tradeDate);

	long countByMarketGroupAndTradeDateAndScoreGreaterThan(String marketGroup, LocalDate tradeDate, double score);

	@Modifying
	@Query("delete from DailyScreen d where d.marketGroup = :group and d.tradeDate = :date")
	int deleteDay(@Param("group") String group, @Param("date") LocalDate date);

	@Modifying
	@Query("delete from DailyScreen d where d.tradeDate < :before")
	int deleteOlderThan(@Param("before") LocalDate before);
}
