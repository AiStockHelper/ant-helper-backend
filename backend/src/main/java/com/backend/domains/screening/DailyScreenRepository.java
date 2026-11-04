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

	@Query("select d from DailyScreen d where d.marketGroup = :group and d.tradeDate = :date and d.direction = 'UP' "
		+ "and d.score > 0 and d.predictedChangeRate > 0 order by d.score desc")
	List<DailyScreen> findRisers(@Param("group") String group, @Param("date") LocalDate date, Pageable pageable);

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
