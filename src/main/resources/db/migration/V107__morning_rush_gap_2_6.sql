-- 모닝러쉬 gap threshold 2.0% → 2.6% (사용자 요청 2026-04-09)
UPDATE morning_rush_config SET gap_threshold_pct = 2.6 WHERE id = 1;
