package com.example.upbit.db;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TradeRepository extends JpaRepository<TradeEntity, Long> {
    List<TradeEntity> findTop200ByOrderByTsEpochMsDesc();
    long countByActionAndTsEpochMsBetween(String action, long fromMs, long toMs);

    /** 특정 마켓에 대한 봇 매수(BUY) 기록이 있는지 확인 */
    long countByMarketAndAction(String market, String action);
}
