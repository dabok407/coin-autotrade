package com.example.upbit.db;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CandleCacheRepository extends JpaRepository<CandleCacheEntity, Long> {

    List<CandleCacheEntity> findByMarketAndIntervalMinOrderByCandleTsUtcAsc(String market, int intervalMin);

    CandleCacheEntity findTopByMarketAndIntervalMinOrderByCandleTsUtcDesc(String market, int intervalMin);

    List<CandleCacheEntity> findByMarketAndIntervalMinAndCandleTsUtcBetweenOrderByCandleTsUtcAsc(
            String market, int intervalMin, String fromUtc, String toUtc);

    boolean existsByMarketAndIntervalMinAndCandleTsUtc(String market, int intervalMin, String candleTsUtc);

    long countByMarketAndIntervalMin(String market, int intervalMin);

    void deleteByMarketAndIntervalMin(String market, int intervalMin);
}
