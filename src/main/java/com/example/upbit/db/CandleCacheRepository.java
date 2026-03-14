package com.example.upbit.db;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CandleCacheRepository extends JpaRepository<CandleCacheEntity, Long> {

    List<CandleCacheEntity> findByMarketAndIntervalMinOrderByCandleTsUtcAsc(String market, int intervalMin);

    CandleCacheEntity findTopByMarketAndIntervalMinOrderByCandleTsUtcDesc(String market, int intervalMin);

    long countByMarketAndIntervalMin(String market, int intervalMin);

    void deleteByMarketAndIntervalMin(String market, int intervalMin);
}
