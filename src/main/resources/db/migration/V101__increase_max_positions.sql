-- 모닝러쉬 최대 포지션 2 → 3
UPDATE morning_rush_config SET max_positions = 3 WHERE id = 1;

-- 오프닝 스캐너 최대 포지션 3 → 5
UPDATE opening_scanner_config SET max_positions = 5 WHERE id = 1;
