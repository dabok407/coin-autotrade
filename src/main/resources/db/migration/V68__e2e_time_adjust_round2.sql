-- V68: 2차 E2E 테스트 시간대 조정 (12:40~)
UPDATE allday_scanner_config SET
  entry_start_hour = 12, entry_start_min = 40
WHERE id = 1;

UPDATE opening_scanner_config SET
  entry_start_hour = 12, entry_start_min = 40,
  entry_end_hour = 12, entry_end_min = 55,
  session_end_hour = 13, session_end_min = 30
WHERE id = 1;
