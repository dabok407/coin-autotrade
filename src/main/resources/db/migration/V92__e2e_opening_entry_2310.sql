-- 오프닝: entry 23:10-23:25, range는 22:00-23:09 (충분한 캔들)
UPDATE opening_scanner_config SET
  range_start_hour = 22, range_start_min = 0,
  range_end_hour = 23, range_end_min = 9,
  entry_start_hour = 23, entry_start_min = 10,
  entry_end_hour = 23, entry_end_min = 25,
  session_end_hour = 23, session_end_min = 40
WHERE id = 1;
