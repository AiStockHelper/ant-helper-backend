package com.backend.market.stock;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.backend.market.Market;

public interface StockMasterRepository extends JpaRepository<StockMaster, Long> {

	Optional<StockMaster> findByMarketAndSymbol(Market market, String symbol);

	List<StockMaster> findBySymbolIgnoreCase(String symbol);

	long countByMarket(Market market);

	List<StockMaster> findByMarketAndSymbolIn(Market market, Collection<String> symbols);

	@Query("""
		select s from StockMaster s
		where s.market in :markets
		  and (lower(s.name) like lower(concat('%', :q, '%'))
		       or lower(s.nameEn) like lower(concat('%', :q, '%'))
		       or lower(s.symbol) like lower(concat(:q, '%')))
		order by
		  case
		    when lower(s.symbol) = lower(:q) then 0
		    when lower(s.name) = lower(:q) or lower(s.nameEn) = lower(:q) then 1
		    when lower(s.name) like lower(concat(:q, '%')) or lower(s.nameEn) like lower(concat(:q, '%'))
		         or lower(s.symbol) like lower(concat(:q, '%')) then 2
		    else 3
		  end,
		  case when s.securityType = 'ST' then 0 else 1 end,
		  length(s.name),
		  s.name
		""")
	List<StockMaster> search(@Param("q") String q, @Param("markets") Collection<Market> markets, Pageable pageable);
}
