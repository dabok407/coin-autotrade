-- V61: E2E 테스트용 PAPER 모드 설정
-- 올데이: PAPER, entry 10:05~22:00, TP 2.3%
UPDATE allday_scanner_config SET
  mode = 'PAPER',
  enabled = true,
  entry_start_hour = 10,
  entry_start_min = 5,
  entry_end_hour = 22,
  entry_end_min = 0,
  quick_tp_pct = 2.30,
  max_positions = 2
WHERE id = 1;

-- 오프닝: PAPER, entry 10:05~10:30
UPDATE opening_scanner_config SET
  mode = 'PAPER',
  enabled = true,
  entry_start_hour = 10,
  entry_start_min = 5,
  entry_end_hour = 10,
  entry_end_min = 30,
  session_end_hour = 11,
  session_end_min = 0
WHERE id = 1;

-- 모닝러쉬: 시간이 하드코딩이라 Hold Phase만 테스트
-- → session_end를 11:00으로 설정, PAPER 모드
UPDATE morning_rush_config SET
  mode = 'PAPER',
  enabled = true,
  session_end_hour = 11,
  session_end_min = 0,
  tp_pct = 2.0,
  sl_pct = 3.0
WHERE id = 1;

-- bot_config: auto_start, capital
UPDATE bot_config SET
  auto_start_enabled = true,
  capital_krw = 1000000
WHERE id = 1;
