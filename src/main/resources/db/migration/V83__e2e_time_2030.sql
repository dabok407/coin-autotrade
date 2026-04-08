UPDATE opening_scanner_config SET
  entry_start_hour = 20, entry_start_min = 30,
  entry_end_hour = 20, entry_end_min = 45
WHERE id = 1;
UPDATE allday_scanner_config SET entry_start_hour = 20, entry_start_min = 30 WHERE id = 1;
