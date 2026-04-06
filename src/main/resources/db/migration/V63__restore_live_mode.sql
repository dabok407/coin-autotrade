-- V63: E2E 테스트 완료 후 LIVE 모드 원복
-- 올데이: LIVE, entry 원복
UPDATE allday_scanner_config SET
  mode = 'LIVE',
  entry_start_hour = 10,
  entry_start_min = 35
WHERE id = 1;

-- 오프닝: LIVE, entry 원복
UPDATE opening_scanner_config SET
  mode = 'LIVE',
  entry_start_hour = 9,
  entry_start_min = 0,
  entry_end_hour = 10,
  entry_end_min = 25,
  session_end_hour = 12,
  session_end_min = 0
WHERE id = 1;

-- 모닝러쉬: LIVE, gap 기준 원복
UPDATE morning_rush_config SET
  mode = 'LIVE',
  gap_threshold_pct = 2.0,
  volume_mult = 5.0,
  confirm_count = 3,
  session_end_hour = 10,
  session_end_min = 0,
  tp_pct = 2.0,
  sl_pct = 3.0
WHERE id = 1;
