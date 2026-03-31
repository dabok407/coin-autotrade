-- V38: KITE 고아 포지션 정리 + 누락된 SELL 기록 복원
-- 2026-03-21 09:55 KITE SELL이 업비트에서 체결됐으나 404 에러로 시스템 미기록

-- 1) KITE SELL 기록 추가 (이미 없는 경우만)
INSERT INTO trade_log (ts_epoch_ms, market, action, price, qty, pnl_krw, roi_percent, mode, pattern_type, pattern_reason, avg_buy_price, candle_unit_min)
SELECT 1742518502000, 'KRW-KITE', 'SELL', 330, 90.09009009, -271, -0.90, 'LIVE', 'SCALP_OPENING_BREAK', 'OPEN_404_RECOVERY', 333, 5
WHERE NOT EXISTS (SELECT 1 FROM trade_log WHERE market = 'KRW-KITE' AND action = 'SELL' AND ts_epoch_ms = 1742518502000);

-- 2) KITE 고아 포지션 삭제
DELETE FROM position WHERE market = 'KRW-KITE';
