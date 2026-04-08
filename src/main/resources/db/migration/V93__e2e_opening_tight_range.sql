-- 오프닝: 레인지를 23:10-23:19 (최근 2-3캔들), entry 23:20-23:35
-- 최근 가격 기반 rangeHigh → 미세 상승만으로도 breakout 가능
UPDATE opening_scanner_config SET
  range_start_hour = 23, range_start_min = 10,
  range_end_hour = 23, range_end_min = 19,
  entry_start_hour = 23, entry_start_min = 20,
  entry_end_hour = 23, entry_end_min = 35,
  session_end_hour = 23, session_end_min = 50
WHERE id = 1;

-- 테스트 포지션 정리
DELETE FROM position WHERE entry_strategy IN ('SCALP_OPENING_BREAK', 'HIGH_CONFIDENCE_BREAKOUT', 'MORNING_RUSH');
