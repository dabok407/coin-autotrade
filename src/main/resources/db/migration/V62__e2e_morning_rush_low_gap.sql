-- V62: E2E 테스트용 모닝러쉬 gap 기준 낮춤 (매수 유도)
UPDATE morning_rush_config SET
  gap_threshold_pct = 0.3,
  volume_mult = 1.0,
  confirm_count = 1,
  session_end_hour = 11,
  session_end_min = 0,
  tp_pct = 1.0,
  sl_pct = 1.5
WHERE id = 1;
