-- V66: SharedPriceService E2E 테스트용 PAPER 모드 + 시간대 조정
-- 올데이: PAPER, entry 12:05~
UPDATE allday_scanner_config SET
  mode = 'PAPER',
  enabled = true,
  entry_start_hour = 12,
  entry_start_min = 5,
  quick_tp_pct = 2.30
WHERE id = 1;

-- 오프닝: PAPER, entry 12:05~12:20
UPDATE opening_scanner_config SET
  mode = 'PAPER',
  enabled = true,
  entry_start_hour = 12,
  entry_start_min = 5,
  entry_end_hour = 12,
  entry_end_min = 20,
  session_end_hour = 13,
  session_end_min = 0
WHERE id = 1;

-- 모닝러쉬: PAPER, gap 낮춤, 세션 13:00
UPDATE morning_rush_config SET
  mode = 'PAPER',
  enabled = true,
  gap_threshold_pct = 0.3,
  volume_mult = 1.0,
  confirm_count = 1,
  session_end_hour = 13,
  session_end_min = 0,
  tp_pct = 2.3,
  sl_pct = 1.5
WHERE id = 1;

-- bot_config
UPDATE bot_config SET
  auto_start_enabled = true,
  capital_krw = 1000000
WHERE id = 1;
