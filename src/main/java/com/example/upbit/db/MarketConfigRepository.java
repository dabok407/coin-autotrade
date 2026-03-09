package com.example.upbit.db;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MarketConfigRepository extends JpaRepository<MarketConfigEntity, Long> {
    Optional<MarketConfigEntity> findByMarket(String market);
    List<MarketConfigEntity> findByEnabledTrueOrderByMarketAsc();
    List<MarketConfigEntity> findAllByOrderByMarketAsc();
}
