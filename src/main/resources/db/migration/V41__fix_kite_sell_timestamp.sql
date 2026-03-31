-- V41: KITE SELL 레코드 타임스탬프 수정
-- V38에서 잘못된 epoch ms(2025년)로 삽입됨 → 2026-03-21 09:55:03 KST로 수정

-- 1) 잘못된 레코드 삭제
DELETE FROM trade_log WHERE market = 'KRW-KITE' AND action = 'SELL' AND ts_epoch_ms = 1742518502000;

-- 2) 올바른 타임스탬프로 재삽입 (2026-03-21 09:55:03 KST = 1774054503000)
INSERT INTO trade_log (ts_epoch_ms, market, action, price, qty, pnl_krw, roi_percent, mode, pattern_type, pattern_reason, avg_buy_price, candle_unit_min)
SELECT 1774054503000, 'KRW-KITE', 'SELL', 330, 90.09009009, -271, -0.90, 'LIVE', 'SCALP_OPENING_BREAK', 'OPEN_404_RECOVERY', 333, 5
WHERE NOT EXISTS (SELECT 1 FROM trade_log WHERE market = 'KRW-KITE' AND action = 'SELL' AND ts_epoch_ms = 1774054503000);
