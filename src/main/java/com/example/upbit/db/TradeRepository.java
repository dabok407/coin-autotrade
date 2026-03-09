package com.example.upbit.db;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TradeRepository extends JpaRepository<TradeEntity, Long> {
    List<TradeEntity> findTop200ByOrderByTsEpochMsDesc();
    long countByActionAndTsEpochMsBetween(String action, long fromMs, long toMs);
}
