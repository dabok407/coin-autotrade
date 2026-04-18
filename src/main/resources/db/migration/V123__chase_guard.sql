-- V123: Chase Guard — 직전 5분봉 2개 high 대비 진입가 거리 상한 (%)
-- 근거: NEAR 제외 재분석 4주 데이터. Opening 1.4% (+18,830원/4주), AllDay 2.1% (+12,003원/4주).
-- MR은 09:00 급등 탐지 구조상 적용 불가 → 컬럼 없음.
-- 0 이하 설정 시 비활성.

ALTER TABLE opening_scanner_config ADD COLUMN IF NOT EXISTS chase_guard_pct DECIMAL(5,2) NOT NULL DEFAULT 1.40;
ALTER TABLE allday_scanner_config ADD COLUMN IF NOT EXISTS chase_guard_pct DECIMAL(5,2) NOT NULL DEFAULT 2.10;

UPDATE opening_scanner_config SET chase_guard_pct = 1.40 WHERE id = 1;
UPDATE allday_scanner_config SET chase_guard_pct = 2.10 WHERE id = 1;
