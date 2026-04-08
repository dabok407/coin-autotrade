-- 오프닝: 레인지 22:10-22:49, entry 22:50-23:00 (캔들 최소 4개 확보)
UPDATE opening_scanner_config SET
  range_start_hour = 22, range_start_min = 10,
  range_end_hour = 22, range_end_min = 49,
  entry_start_hour = 22, entry_start_min = 50,
  entry_end_hour = 23, entry_end_min = 5,
  session_end_hour = 23, session_end_min = 20
WHERE id = 1;

-- 모닝러쉬: max_positions 늘리기
UPDATE morning_rush_config SET
  max_positions = 10
WHERE id = 1;

-- 테스트 포지션 정리 (이전 TP 테스트 잔여분)
DELETE FROM position WHERE entry_strategy IN ('SCALP_OPENING_BREAK', 'HIGH_CONFIDENCE_BREAKOUT', 'MORNING_RUSH');
