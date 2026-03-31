-- V37: 2026-03-20 오프닝 스캐너 BUY 기록 복원
-- 원인: OpeningScannerService.executeBuy()에서 pnl_krw, roi_percent NOT NULL 컬럼 미설정으로
--       TradeEntity 저장 실패 → 포지션까지 롤백됨. 업비트에서는 체결됐으나 봇 DB에 기록 누락.
-- 데이터 출처: 업비트 앱 거래내역 캡처 + 시스템 SELL 기록의 avg_buy_price
-- epoch_ms: KST 시간을 UTC로 변환 (KST-9h) 후 epoch 계산

-- CFG: 09:10:03 KST → 00:10:03 UTC → 1773965403000
INSERT INTO trade_log (ts_epoch_ms, market, action, price, qty, pnl_krw, roi_percent, mode, pattern_type, pattern_reason, candle_unit_min)
SELECT 1773965403000, 'KRW-CFG', 'BUY', 218, 137.61467889, 0, 0, 'LIVE', 'SCALP_OPENING_BREAK', 'BUY record restored from Upbit transaction history', 5
WHERE NOT EXISTS (SELECT 1 FROM trade_log WHERE market='KRW-CFG' AND action='BUY' AND ts_epoch_ms BETWEEN 1773965100000 AND 1773965700000);

-- SOL: 09:15:03 KST → 00:15:03 UTC → 1773965703000
INSERT INTO trade_log (ts_epoch_ms, market, action, price, qty, pnl_krw, roi_percent, mode, pattern_type, pattern_reason, candle_unit_min)
SELECT 1773965703000, 'KRW-SOL', 'BUY', 132900, 0.22573363, 0, 0, 'LIVE', 'SCALP_OPENING_BREAK', 'BUY record restored from Upbit transaction history', 5
WHERE NOT EXISTS (SELECT 1 FROM trade_log WHERE market='KRW-SOL' AND action='BUY' AND ts_epoch_ms BETWEEN 1773965400000 AND 1773966000000);

-- ATH: 09:20:03 KST → 00:20:03 UTC → 1773966003000
INSERT INTO trade_log (ts_epoch_ms, market, action, price, qty, pnl_krw, roi_percent, mode, pattern_type, pattern_reason, candle_unit_min)
SELECT 1773966003000, 'KRW-ATH', 'BUY', 10.5, 2857.14285714, 0, 0, 'LIVE', 'SCALP_OPENING_BREAK', 'BUY record restored from Upbit transaction history', 5
WHERE NOT EXISTS (SELECT 1 FROM trade_log WHERE market='KRW-ATH' AND action='BUY' AND ts_epoch_ms BETWEEN 1773965700000 AND 1773966300000);

-- SAHARA: 09:25:03 KST → 00:25:03 UTC → 1773966303000
INSERT INTO trade_log (ts_epoch_ms, market, action, price, qty, pnl_krw, roi_percent, mode, pattern_type, pattern_reason, candle_unit_min)
SELECT 1773966303000, 'KRW-SAHARA', 'BUY', 41.2, 728.15533980, 0, 0, 'LIVE', 'SCALP_OPENING_BREAK', 'BUY record restored from Upbit transaction history', 5
WHERE NOT EXISTS (SELECT 1 FROM trade_log WHERE market='KRW-SAHARA' AND action='BUY' AND ts_epoch_ms BETWEEN 1773966000000 AND 1773966600000);
