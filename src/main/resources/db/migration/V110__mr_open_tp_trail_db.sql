-- 모닝러쉬/오프닝 TP_TRAIL DB화 (2026-04-14)
-- 하드코딩 제거 → DB에서 조정 가능

-- 모닝러쉬: TP_TRAIL drop 1.0% → 1.5%
ALTER TABLE morning_rush_config ADD COLUMN IF NOT EXISTS tp_trail_drop_pct DECIMAL(5,2) DEFAULT 1.5;

-- 오프닝: TP_TRAIL 활성화 + drop DB화
ALTER TABLE opening_scanner_config ADD COLUMN IF NOT EXISTS tp_trail_activate_pct DECIMAL(5,2) DEFAULT 1.5;
ALTER TABLE opening_scanner_config ADD COLUMN IF NOT EXISTS tp_trail_drop_pct DECIMAL(5,2) DEFAULT 1.0;

-- 설정 적용
UPDATE morning_rush_config SET tp_trail_drop_pct = 1.5 WHERE id = 1;
UPDATE opening_scanner_config SET tp_trail_activate_pct = 1.5, tp_trail_drop_pct = 1.0 WHERE id = 1;
