-- 오프닝: range 21:00, entry 21:40~21:55
UPDATE opening_scanner_config SET
  range_start_hour = 21, range_start_min = 0,
  range_end_hour = 21, range_end_min = 35,
  entry_start_hour = 21, entry_start_min = 40,
  entry_end_hour = 21, entry_end_min = 55,
  session_end_hour = 22, session_end_min = 10
WHERE id = 1;
-- 올데이: entry 21:40~, TP 0.03 유지
UPDATE allday_scanner_config SET
  entry_start_hour = 21, entry_start_min = 40,
  quick_tp_pct = 0.03
WHERE id = 1;
