-- V133: Phase 3 — 큰추세 보존 + BEV 보장 + AD 야간 집중
-- 근거: .analysis/FINAL_COMPREHENSIVE_REPORT_2026-05-02.md
--   1) Trail Ratchet peak≥5 drop 4.0 → 5.0 — 큰 추세 더 길게 보존 (Agent F 권고)
--   2) BEV(Break-Even) 보장 — 큰추세(peak 5%+) 진입 후 ROI < 0 으로 가면 즉시 매도
--      Agent F: 큰 trail 폭으로 인한 음전 위험 제한
--   3) AD entry_end 22:00 → 23:00 — 야간 19~22시 집중 시간대 + 23시까지 진입 허용
--      Agent C: 야간 5%+ 32건/4일 vs AD 진입 2건 — 시간대 미스매치 추가 보완
--
-- Entity default는 V130 호환(BEV 비활성). 운영값은 UPDATE로 적용.

-- ============================================================
-- (1) Trail Ratchet peak≥5 drop 4.0 → 5.0 (3 스캐너)
-- ============================================================
UPDATE morning_rush_config    SET split_1st_drop_above_5 = 5.0,
                                  trail_after_drop_above_5 = 5.0
                              WHERE id = 1;
UPDATE opening_scanner_config SET split_1st_drop_above_5 = 5.0,
                                  trail_after_drop_above_5 = 5.0
                              WHERE id = 1;
UPDATE allday_scanner_config  SET split_1st_drop_above_5 = 5.0,
                                  trail_after_drop_above_5 = 5.0
                              WHERE id = 1;

-- ============================================================
-- (2) BEV 보장 — 큰추세 진입 후 음전 차단 (3 스캐너)
--     bev_guard_enabled = TRUE: peak >= bev_trigger_pct 도달했던 종목이
--                                현재 ROI < 0 으로 가면 즉시 매도
--     0=비활성(V130 호환). 운영값: enabled=true, trigger 5.0%
-- ============================================================
ALTER TABLE morning_rush_config    ADD COLUMN IF NOT EXISTS bev_guard_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE morning_rush_config    ADD COLUMN IF NOT EXISTS bev_trigger_pct DECIMAL(4,2) NOT NULL DEFAULT 5.0;

ALTER TABLE opening_scanner_config ADD COLUMN IF NOT EXISTS bev_guard_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE opening_scanner_config ADD COLUMN IF NOT EXISTS bev_trigger_pct DECIMAL(4,2) NOT NULL DEFAULT 5.0;

ALTER TABLE allday_scanner_config  ADD COLUMN IF NOT EXISTS bev_guard_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE allday_scanner_config  ADD COLUMN IF NOT EXISTS bev_trigger_pct DECIMAL(4,2) NOT NULL DEFAULT 5.0;

-- 운영값 활성화
UPDATE morning_rush_config    SET bev_guard_enabled = TRUE WHERE id = 1;
UPDATE opening_scanner_config SET bev_guard_enabled = TRUE WHERE id = 1;
UPDATE allday_scanner_config  SET bev_guard_enabled = TRUE WHERE id = 1;

-- ============================================================
-- (3) AD entry window 22:00 → 23:00 (야간 추가 1시간)
-- ============================================================
UPDATE allday_scanner_config SET entry_end_hour = 23, entry_end_min = 0 WHERE id = 1;
