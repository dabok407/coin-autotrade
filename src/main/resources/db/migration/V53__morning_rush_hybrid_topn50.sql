-- V53: 모닝 러쉬 하이브리드 진입 + 전 스캐너 TOP N 50 확대 + 저가 필터 비활성화
--
-- 1. 모닝러쉬: gap 5% → 2% 완화 + 캔들 중간 급등(surge) 감지 추가
--    - surge_threshold_pct: 30초 내 3% 이상 급등 시 진입
--    - 하이브리드: gap OR surge 둘 중 하나 충족 시 진입
-- 2. 전 스캐너 TOP N: 15/15/30 → 50/50/50
-- 3. 저가 필터: -1 (비활성화) — 저가 코인도 스캔 대상에 포함

-- 모닝러쉬: surge 컬럼 추가
ALTER TABLE morning_rush_config ADD COLUMN surge_threshold_pct DECIMAL(5,2) NOT NULL DEFAULT 3.0;
ALTER TABLE morning_rush_config ADD COLUMN surge_window_sec INT NOT NULL DEFAULT 30;

-- 모닝러쉬: gap 임계값 2%로 완화
UPDATE morning_rush_config SET gap_threshold_pct = 2.0 WHERE id = 1;

-- TOP N 50으로 확대
UPDATE opening_scanner_config SET top_n = 50 WHERE id = 1;
UPDATE allday_scanner_config SET top_n = 50 WHERE id = 1;
UPDATE morning_rush_config SET top_n = 50 WHERE id = 1;

-- 저가 필터 비활성화 (-1 = OFF)
UPDATE opening_scanner_config SET min_price_krw = -1 WHERE id = 1;
UPDATE allday_scanner_config SET min_price_krw = -1 WHERE id = 1;
UPDATE morning_rush_config SET min_price_krw = -1 WHERE id = 1;
