-- ===========================================
-- V98 원복 오류 수정
-- ===========================================

-- 올데이 sl_pct: 1.5 → 3.0 (V55에서 3.0으로 변경된 값)
UPDATE allday_scanner_config SET sl_pct = 3.0 WHERE id = 1;

-- 오프닝 volume_mult: 2.0 → 5.0 (V54에서 5.0으로 변경된 값)
UPDATE opening_scanner_config SET volume_mult = 5.0 WHERE id = 1;

-- 오프닝 trail_atr_mult: 0.7 → 0.6 (V32에서 0.6으로 변경된 값)
UPDATE opening_scanner_config SET trail_atr_mult = 0.6 WHERE id = 1;
