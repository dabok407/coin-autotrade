-- V72: 4차 E2E 시간대 (13:30~)
UPDATE allday_scanner_config SET entry_start_hour = 13, entry_start_min = 30 WHERE id = 1;
UPDATE opening_scanner_config SET
  entry_start_hour = 13, entry_start_min = 30,
  entry_end_hour = 13, entry_end_min = 45
WHERE id = 1;
