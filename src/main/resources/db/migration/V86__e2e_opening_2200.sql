UPDATE opening_scanner_config SET
  range_start_hour = 21, range_start_min = 30,
  range_end_hour = 21, range_end_min = 59,
  entry_start_hour = 22, entry_start_min = 0,
  entry_end_hour = 22, entry_end_min = 10,
  session_end_hour = 22, session_end_min = 20
WHERE id = 1;
