-- V67: TP 0.5%로 낮춰서 TP 트리거 검증
UPDATE morning_rush_config SET tp_pct = 0.5 WHERE id = 1;
UPDATE allday_scanner_config SET quick_tp_pct = 0.5 WHERE id = 1;

-- 테스트 포지션 정리 (이전 테스트 잔여)
DELETE FROM position WHERE market IN ('KRW-T', 'KRW-XPL', 'KRW-DRIFT');
DELETE FROM trade_log WHERE mode = 'PAPER' AND ts_epoch_ms > 1775282400000;
