-- 오프닝 SL 테스트: TP 극대화(99), SL 0.1%
UPDATE opening_scanner_config SET
  tp_atr_mult = 99.0,
  sl_pct = 0.1,
  trail_atr_mult = 99.0,
  range_start_hour = 23, range_start_min = 38,
  range_end_hour = 23, range_end_min = 47,
  entry_start_hour = 23, entry_start_min = 48,
  entry_end_hour = 23, entry_end_min = 58,
  session_end_hour = 0, session_end_min = 10
WHERE id = 1;

DELETE FROM position WHERE entry_strategy IN ('SCALP_OPENING_BREAK', 'HIGH_CONFIDENCE_BREAKOUT', 'MORNING_RUSH');
