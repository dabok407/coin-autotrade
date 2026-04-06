-- V71: TP 0.03%로 극한 낮춰서 TP 트리거 검증
UPDATE morning_rush_config SET tp_pct = 0.03 WHERE id = 1;
UPDATE allday_scanner_config SET quick_tp_pct = 0.03 WHERE id = 1;

-- 이전 테스트 포지션 정리
DELETE FROM position WHERE market IN ('KRW-KERNEL', 'KRW-1INCH', 'KRW-THETA');
