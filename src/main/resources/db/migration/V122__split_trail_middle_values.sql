-- V122: Split-Exit 트레일 중간값 적용 (단계적 완화)
-- 근거: 1차 완화로 SPLIT_1ST_TRAIL_DROP/TRAIL_DROP_AFTER_SPLIT를 중간값으로 조정.
-- 한번에 크게 올리지 않고, 3개 스캐너 모두 중간값으로 점진 조정하여 반응 관찰.

-- MorningRush: 0.50 → 0.65 / 1.20 → 1.40
UPDATE morning_rush_config
SET split_1st_trail_drop = 0.65,
    trail_drop_after_split = 1.40
WHERE id = 1;

-- Opening: 0.40 → 0.45 / 1.00 → 1.20
UPDATE opening_scanner_config
SET split_1st_trail_drop = 0.45,
    trail_drop_after_split = 1.20
WHERE id = 1;

-- AllDay: 0.70 → 0.85 / 1.00 → 1.20
UPDATE allday_scanner_config
SET split_1st_trail_drop = 0.85,
    trail_drop_after_split = 1.20
WHERE id = 1;
