-- ===========================================
-- E2E: 모닝러쉬 SL + 오프닝 TP/SL 동시 테스트
-- ===========================================

-- 모닝러쉬: SL 테스트 (TP 극대화, SL 0.1%)
UPDATE morning_rush_config SET
  tp_pct = 99.0,
  sl_pct = 0.1,
  session_end_hour = 23, session_end_min = 30
WHERE id = 1;

-- 오프닝: TP 0.01 ATR, SL 0.1% (둘 다 극히 낮게 → 매수 직후 TP 또는 SL 발동)
UPDATE opening_scanner_config SET
  range_start_hour = 22, range_start_min = 0,
  range_end_hour = 22, range_end_min = 59,
  entry_start_hour = 23, entry_start_min = 0,
  entry_end_hour = 23, entry_end_min = 15,
  session_end_hour = 23, session_end_min = 30,
  tp_atr_mult = 0.01,
  sl_pct = 0.1,
  trail_atr_mult = 0.01
WHERE id = 1;

-- 테스트 포지션 정리
DELETE FROM position WHERE entry_strategy IN ('SCALP_OPENING_BREAK', 'HIGH_CONFIDENCE_BREAKOUT', 'MORNING_RUSH');
