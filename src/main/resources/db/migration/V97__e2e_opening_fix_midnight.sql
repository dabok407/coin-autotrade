-- session_end 자정 넘김 방지 (23:59)
UPDATE opening_scanner_config SET
  session_end_hour = 23, session_end_min = 59,
  range_start_hour = 23, range_start_min = 48,
  range_end_hour = 23, range_end_min = 54,
  entry_start_hour = 23, entry_start_min = 55,
  entry_end_hour = 23, entry_end_min = 59
WHERE id = 1;

DELETE FROM position WHERE entry_strategy IN ('SCALP_OPENING_BREAK', 'HIGH_CONFIDENCE_BREAKOUT', 'MORNING_RUSH');
