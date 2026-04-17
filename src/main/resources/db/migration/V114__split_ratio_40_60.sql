-- Split-Exit 1차/2차 매도 비율을 50/50 → 40/60으로 변경
-- 1차 매도 40% (빠른 수익 확정), 2차 매도 60% (TRAIL로 상방 확장)
UPDATE morning_rush_config    SET split_ratio = 0.40 WHERE id = 1;
UPDATE opening_scanner_config SET split_ratio = 0.40 WHERE id = 1;
UPDATE allday_scanner_config  SET split_ratio = 0.40 WHERE id = 1;
