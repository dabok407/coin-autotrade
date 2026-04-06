-- V76: 오프닝-올데이 갭 제거 (10:30 기준)
UPDATE opening_scanner_config SET entry_end_hour = 10, entry_end_min = 30 WHERE id = 1;
UPDATE allday_scanner_config SET entry_start_hour = 10, entry_start_min = 30 WHERE id = 1;
