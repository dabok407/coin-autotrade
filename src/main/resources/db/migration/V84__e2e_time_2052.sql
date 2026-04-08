UPDATE opening_scanner_config SET
  entry_start_hour = 20, entry_start_min = 52,
  entry_end_hour = 21, entry_end_min = 5
WHERE id = 1;
UPDATE allday_scanner_config SET entry_start_hour = 20, entry_start_min = 52 WHERE id = 1;
