-- ============================================================
-- SL 종합안 컬럼 추가 (옵션 B 백테스트 검증 결과 반영)
-- A+SESSION 조합 적용: SL 1~15분 -5% / 15분 이후 -3% / SESSION 익일 08:50
--
-- TOP-N 차등 SL_WIDE는 90일 백테스트에서 역효과 검증 (-0.22%p)
-- → 차등 컬럼은 만들지만 모두 동일 값(5.0)으로 설정 = 사실상 단일 SL_WIDE
-- 향후 차등이 효과적이라고 검증되면 화면에서 조정 가능
-- ============================================================

-- 모닝러쉬: SL 종합안 컬럼 (90일 백테스트 검증값 적용)
-- 백테스트 결과: SL_WIDE 30분 -6.0%가 최적 (B 그룹 평균 깊이 -5.94%)
ALTER TABLE morning_rush_config ADD COLUMN IF NOT EXISTS grace_period_sec INT NOT NULL DEFAULT 60;
ALTER TABLE morning_rush_config ADD COLUMN IF NOT EXISTS wide_period_min INT NOT NULL DEFAULT 30;
ALTER TABLE morning_rush_config ADD COLUMN IF NOT EXISTS wide_sl_pct DECIMAL(5,2) NOT NULL DEFAULT 6.0;

-- 오프닝: SL 종합안 + TOP-N 차등 컬럼 (모두 동일값 6.0으로 단일 SL_WIDE 효과)
ALTER TABLE opening_scanner_config ADD COLUMN IF NOT EXISTS grace_period_sec INT NOT NULL DEFAULT 60;
ALTER TABLE opening_scanner_config ADD COLUMN IF NOT EXISTS wide_period_min INT NOT NULL DEFAULT 15;
ALTER TABLE opening_scanner_config ADD COLUMN IF NOT EXISTS wide_sl_top10_pct DECIMAL(5,2) NOT NULL DEFAULT 6.0;
ALTER TABLE opening_scanner_config ADD COLUMN IF NOT EXISTS wide_sl_top20_pct DECIMAL(5,2) NOT NULL DEFAULT 6.0;
ALTER TABLE opening_scanner_config ADD COLUMN IF NOT EXISTS wide_sl_top50_pct DECIMAL(5,2) NOT NULL DEFAULT 6.0;
ALTER TABLE opening_scanner_config ADD COLUMN IF NOT EXISTS wide_sl_other_pct DECIMAL(5,2) NOT NULL DEFAULT 6.0;
ALTER TABLE opening_scanner_config ADD COLUMN IF NOT EXISTS tight_sl_pct DECIMAL(5,2) NOT NULL DEFAULT 3.0;

-- 오프닝: SESSION_END 익일 08:50 (오버나잇 세션 활성화) — A+SESSION 검증 결과
UPDATE opening_scanner_config SET session_end_hour = 8, session_end_min = 50 WHERE id = 1;
