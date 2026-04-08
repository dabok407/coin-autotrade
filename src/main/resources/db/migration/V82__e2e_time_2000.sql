UPDATE morning_rush_config SET session_end_hour = 20, session_end_min = 30 WHERE id = 1;
UPDATE opening_scanner_config SET
  entry_start_hour = 20, entry_start_min = 0,
  entry_end_hour = 20, entry_end_min = 15,
  session_end_hour = 20, session_end_min = 30
WHERE id = 1;
UPDATE allday_scanner_config SET entry_start_hour = 20, entry_start_min = 0 WHERE id = 1;
