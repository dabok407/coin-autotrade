-- V80: 시간대 재조정 19:38~
UPDATE morning_rush_config SET session_end_hour = 20, session_end_min = 0 WHERE id = 1;
UPDATE opening_scanner_config SET
  entry_start_hour = 19, entry_start_min = 38,
  entry_end_hour = 19, entry_end_min = 50,
  session_end_hour = 20, session_end_min = 0
WHERE id = 1;
UPDATE allday_scanner_config SET
  entry_start_hour = 19, entry_start_min = 38
WHERE id = 1;
