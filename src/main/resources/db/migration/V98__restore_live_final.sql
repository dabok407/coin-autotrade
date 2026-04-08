-- ===========================================
-- LIVE 설정 최종 원복 (E2E 테스트 완료)
-- ===========================================

-- 모닝러쉬: LIVE 모드, 정상 파라미터
UPDATE morning_rush_config SET
  mode = 'LIVE',
  session_end_hour = 10, session_end_min = 0,
  gap_threshold_pct = 2.0,
  volume_mult = 5.0,
  confirm_count = 3,
  tp_pct = 2.3,
  sl_pct = 3.0,
  max_positions = 2
WHERE id = 1;

-- 오프닝 스캐너: LIVE 모드, 정상 시간대/파라미터
UPDATE opening_scanner_config SET
  mode = 'LIVE',
  range_start_hour = 8, range_start_min = 0,
  range_end_hour = 8, range_end_min = 59,
  entry_start_hour = 9, entry_start_min = 0,
  entry_end_hour = 10, entry_end_min = 30,
  session_end_hour = 12, session_end_min = 0,
  tp_atr_mult = 1.5,
  sl_pct = 2.0,
  trail_atr_mult = 0.7,
  volume_mult = 2.0,
  min_body_ratio = 0.45
WHERE id = 1;

-- 종일 스캐너: LIVE 모드, 정상 시간대
UPDATE allday_scanner_config SET
  mode = 'LIVE',
  entry_start_hour = 10, entry_start_min = 30,
  entry_end_hour = 22, entry_end_min = 0,
  quick_tp_pct = 2.3,
  sl_pct = 1.5,
  min_confidence = 7.5
WHERE id = 1;

-- 봇 설정: 자본금 원복
UPDATE bot_config SET
  capital_krw = 600000
WHERE id = 1;

-- 테스트 PAPER 포지션 정리
DELETE FROM position WHERE entry_strategy IN ('SCALP_OPENING_BREAK', 'HIGH_CONFIDENCE_BREAKOUT', 'MORNING_RUSH');
