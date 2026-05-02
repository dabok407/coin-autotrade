-- V132: Phase 2 — 동적 ATR SL + AD HC 9.0 완화 + entry window 확대
-- 근거: .analysis/FINAL_COMPREHENSIVE_REPORT_2026-05-02.md
--   1) 동적 ATR SL — Agent G 권고: avgLoss 6,180 → 4,000원, PF 0.39 → 1.1 핵심
--      진입 시점 ATR(14, 1분봉) 측정 → SL_TIGHT = clamp(atr_pct * mult, min, max)
--   2) AD HC_BREAKOUT 9.4 → 9.0 — Agent C 권고: 야간 5%+ 32건 vs AD 진입 2건
--   3) AD entry window 10:30~22:00 → 09:00~22:00 — Agent C 권고
--      OP 시간대(~10:30)와 겹치는 부분은 cross_scanner_lock으로 차단
--
-- 단계: Phase 2 (Phase 1 후속). avgLoss 감소가 PF 1.0 돌파 핵심.
-- Entity default는 V130 호환(0=비활성)으로 두고, 운영값은 UPDATE로 적용.

-- ============================================================
-- (1) 동적 ATR SL — 신규 컬럼 (3 entity)
-- ============================================================
ALTER TABLE morning_rush_config    ADD COLUMN IF NOT EXISTS sl_atr_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE morning_rush_config    ADD COLUMN IF NOT EXISTS sl_atr_mult DECIMAL(4,2) NOT NULL DEFAULT 1.5;
ALTER TABLE morning_rush_config    ADD COLUMN IF NOT EXISTS sl_atr_min_pct DECIMAL(4,2) NOT NULL DEFAULT 1.5;
ALTER TABLE morning_rush_config    ADD COLUMN IF NOT EXISTS sl_atr_max_pct DECIMAL(4,2) NOT NULL DEFAULT 3.5;

ALTER TABLE opening_scanner_config ADD COLUMN IF NOT EXISTS sl_atr_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE opening_scanner_config ADD COLUMN IF NOT EXISTS sl_atr_mult DECIMAL(4,2) NOT NULL DEFAULT 1.5;
ALTER TABLE opening_scanner_config ADD COLUMN IF NOT EXISTS sl_atr_min_pct DECIMAL(4,2) NOT NULL DEFAULT 1.5;
ALTER TABLE opening_scanner_config ADD COLUMN IF NOT EXISTS sl_atr_max_pct DECIMAL(4,2) NOT NULL DEFAULT 3.5;

ALTER TABLE allday_scanner_config  ADD COLUMN IF NOT EXISTS sl_atr_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE allday_scanner_config  ADD COLUMN IF NOT EXISTS sl_atr_mult DECIMAL(4,2) NOT NULL DEFAULT 1.5;
ALTER TABLE allday_scanner_config  ADD COLUMN IF NOT EXISTS sl_atr_min_pct DECIMAL(4,2) NOT NULL DEFAULT 1.5;
ALTER TABLE allday_scanner_config  ADD COLUMN IF NOT EXISTS sl_atr_max_pct DECIMAL(4,2) NOT NULL DEFAULT 3.5;

-- 주의: ATR SL Service 적용 로직은 Phase 2b(별건)에서 추가 예정.
-- 지금은 Entity/DB 컬럼만 준비. sl_atr_enabled=FALSE 유지 (V130 호환).
-- Phase 2b 완료 후 UPDATE sl_atr_enabled = TRUE 별도 마이그레이션으로 활성화.

-- ============================================================
-- (2) AD HC_BREAKOUT 점수 9.4 → 9.0 완화
--     Agent C: 4일간 AD 실진입 2건뿐. 야간 5%+ 32건 미포착.
-- ============================================================
UPDATE allday_scanner_config SET min_confidence = 9.0 WHERE id = 1;

-- ============================================================
-- (3) AD entry window 10:30 → 09:00 확대
--     OP 시간대 충돌은 cross_scanner_lock(V130 ③)으로 차단
-- ============================================================
UPDATE allday_scanner_config SET entry_start_hour = 9, entry_start_min = 0 WHERE id = 1;
