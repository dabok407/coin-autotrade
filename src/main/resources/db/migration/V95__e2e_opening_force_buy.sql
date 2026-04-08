-- 오프닝: 레인지 23:28-23:37, entry 23:38-23:50
-- 모든 필터 비활성화 상태이므로 진입 가능
UPDATE opening_scanner_config SET
  range_start_hour = 23, range_start_min = 28,
  range_end_hour = 23, range_end_min = 37,
  entry_start_hour = 23, entry_start_min = 38,
  entry_end_hour = 23, entry_end_min = 50,
  session_end_hour = 23, session_end_min = 59
WHERE id = 1;

DELETE FROM position WHERE entry_strategy IN ('SCALP_OPENING_BREAK', 'HIGH_CONFIDENCE_BREAKOUT', 'MORNING_RUSH');
