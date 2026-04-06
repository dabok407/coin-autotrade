-- V70: 3차 E2E 테스트 시간대 (13:10~)
UPDATE allday_scanner_config SET
  entry_start_hour = 13, entry_start_min = 10
WHERE id = 1;

UPDATE opening_scanner_config SET
  entry_start_hour = 13, entry_start_min = 10,
  entry_end_hour = 13, entry_end_min = 25,
  session_end_hour = 14, session_end_min = 0
WHERE id = 1;

UPDATE morning_rush_config SET
  session_end_hour = 14, session_end_min = 0
WHERE id = 1;
