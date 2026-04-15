-- Split-Exit 1차/2차 매도 비율을 60/40 → 50/50으로 변경
UPDATE morning_rush_config  SET split_ratio = 0.50 WHERE id = 1;
UPDATE opening_scanner_config SET split_ratio = 0.50 WHERE id = 1;
UPDATE allday_scanner_config  SET split_ratio = 0.50 WHERE id = 1;
