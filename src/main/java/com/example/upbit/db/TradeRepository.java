package com.example.upbit.db;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TradeRepository extends JpaRepository<TradeEntity, Long> {
    List<TradeEntity> findTop200ByOrderByTsEpochMsDesc();
    long countByActionAndTsEpochMsBetween(String action, long fromMs, long toMs);

    /** 특정 마켓에 대한 봇 매수(BUY) 기록이 있는지 확인 */
    long countByMarketAndAction(String market, String action);

    /** 특정 마켓의 가장 최근 BUY/SELL 트레이드 조회 (Quick TP confidence 참조용) */
    TradeEntity findTop1ByMarketAndActionOrderByTsEpochMsDesc(String market, String action);

    /** 최근 1시간 내 BUY 기록 조회 (서버 재시작 시 throttle 복원용, 2026-04-11) */
    List<TradeEntity> findByActionAndTsEpochMsGreaterThanEqual(String action, long tsEpochMs);

    /**
     * V130 ③: 동일 종목 손실 청산 이력 존재 여부.
     * sinceMs 이후 market의 SELL 중 roi_percent < 0 인 건이 있으면 true.
     */
    @org.springframework.data.jpa.repository.Query(
        "SELECT COUNT(t) > 0 FROM TradeEntity t " +
        "WHERE t.market = ?1 AND t.action = 'SELL' " +
        "AND t.tsEpochMs >= ?2 AND t.roiPercent < 0")
    boolean existsRecentLoss(String market, long sinceMs);
}
