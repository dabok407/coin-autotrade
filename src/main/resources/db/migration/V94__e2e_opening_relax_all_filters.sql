-- 오프닝: 모든 필터 극한 완화 (매수 테스트용)
UPDATE opening_scanner_config SET
  volume_mult = 0.1,
  min_body_ratio = 0.01,
  range_start_hour = 23, range_start_min = 20,
  range_end_hour = 23, range_end_min = 29,
  entry_start_hour = 23, entry_start_min = 30,
  entry_end_hour = 23, entry_end_min = 45,
  session_end_hour = 23, session_end_min = 55
WHERE id = 1;

-- 포지션 정리
DELETE FROM position WHERE entry_strategy IN ('SCALP_OPENING_BREAK', 'HIGH_CONFIDENCE_BREAKOUT', 'MORNING_RUSH');
