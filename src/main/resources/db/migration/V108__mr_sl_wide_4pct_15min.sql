-- 모닝러쉬 SL_WIDE 축소 (2026-04-13)
-- 6% / 30분 → 4% / 15분 (큰 손실 제한, ANKR -6.04% 방지)
UPDATE morning_rush_config SET wide_sl_pct = 4.0, wide_period_min = 15 WHERE id = 1;
