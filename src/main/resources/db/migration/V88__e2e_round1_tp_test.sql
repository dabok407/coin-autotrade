-- ===========================================
-- E2E Round 1: TP 테스트 (SL 극대화하여 TP만 발동되도록)
-- ===========================================

-- 모닝러쉬: TP 0.1%, SL 99% (TP만 테스트)
UPDATE morning_rush_config SET
  mode = 'PAPER',
  tp_pct = 0.1,
  sl_pct = 99.0,
  session_end_hour = 23, session_end_min = 10,
  gap_threshold_pct = 0.1,
  volume_mult = 1.0,
  confirm_count = 1
WHERE id = 1;

-- 오프닝: TP ATR 0.01배 (매우 낮게 → 즉시 TP 도달), SL 99%
UPDATE opening_scanner_config SET
  mode = 'PAPER',
  range_start_hour = 22, range_start_min = 30,
  range_end_hour = 22, range_end_min = 41,
  entry_start_hour = 22, entry_start_min = 42,
  entry_end_hour = 22, entry_end_min = 55,
  session_end_hour = 23, session_end_min = 10,
  tp_atr_mult = 0.01,
  sl_pct = 99.0,
  trail_atr_mult = 0.01
WHERE id = 1;

-- 종일 스캐너: PAPER, 진입 활성화 (동시 실행)
UPDATE allday_scanner_config SET
  mode = 'PAPER',
  entry_start_hour = 22, entry_start_min = 42,
  entry_end_hour = 23, entry_end_min = 0,
  quick_tp_pct = 0.1,
  sl_pct = 99.0,
  min_confidence = 5.0
WHERE id = 1;

-- 자본금 테스트용 확대
UPDATE bot_config SET
  capital_krw = 5000000
WHERE id = 1;

-- 테스트 포지션 정리
DELETE FROM position WHERE entry_strategy IN ('SCALP_OPENING_BREAK', 'HIGH_CONFIDENCE_BREAKOUT', 'MORNING_RUSH');
