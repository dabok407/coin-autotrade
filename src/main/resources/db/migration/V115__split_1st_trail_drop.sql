-- Split-Exit 1차/2차 매도 모두 TRAIL 방식으로 통일 (Y-A 설계)
-- 1차 매도: split_1st_trail_drop 컬럼 신규 추가 (기본 0.50%)
--   → split_tp_pct 도달 후 peak 대비 drop >= split_1st_trail_drop 시 1차 매도
-- 2차 매도: trail_drop_after_split 1.0% → 1.2% 상향 (일시 조정 견디기)

-- 1. 신규 컬럼: 1차 TRAIL drop %
ALTER TABLE morning_rush_config    ADD COLUMN IF NOT EXISTS split_1st_trail_drop DECIMAL(5,2) DEFAULT 0.50;
ALTER TABLE opening_scanner_config ADD COLUMN IF NOT EXISTS split_1st_trail_drop DECIMAL(5,2) DEFAULT 0.50;
ALTER TABLE allday_scanner_config  ADD COLUMN IF NOT EXISTS split_1st_trail_drop DECIMAL(5,2) DEFAULT 0.50;

-- 2. 2차 TRAIL drop 상향: 1.0 → 1.2
UPDATE morning_rush_config    SET trail_drop_after_split = 1.20 WHERE id = 1;
UPDATE opening_scanner_config SET trail_drop_after_split = 1.20 WHERE id = 1;
UPDATE allday_scanner_config  SET trail_drop_after_split = 1.20 WHERE id = 1;
