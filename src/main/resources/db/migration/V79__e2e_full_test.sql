-- V79: 전체 통합테스트 — 모닝러쉬/오프닝/올데이 PAPER + 기준 완화

-- 모닝러쉬: PAPER, gap 0.01%, confirm 1, TP 0.03%, SL 0.5%
UPDATE morning_rush_config SET
  mode = 'PAPER',
  enabled = true,
  gap_threshold_pct = 0.01,
  volume_mult = 1.0,
  confirm_count = 1,
  tp_pct = 0.03,
  sl_pct = 0.5,
  session_end_hour = 16,
  session_end_min = 30
WHERE id = 1;

-- 오프닝: PAPER, entry 16:05~16:20
UPDATE opening_scanner_config SET
  mode = 'PAPER',
  enabled = true,
  entry_start_hour = 16,
  entry_start_min = 5,
  entry_end_hour = 16,
  entry_end_min = 20,
  session_end_hour = 16,
  session_end_min = 40
WHERE id = 1;

-- 올데이: PAPER, entry 16:05~
UPDATE allday_scanner_config SET
  mode = 'PAPER',
  enabled = true,
  entry_start_hour = 16,
  entry_start_min = 5,
  quick_tp_pct = 0.03,
  min_confidence = 5.0
WHERE id = 1;
