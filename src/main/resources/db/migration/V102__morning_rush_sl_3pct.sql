-- 모닝러쉬 SL 종합안 적용
-- sl_pct는 5분 이후 타이트닝 값 (3%) — 60s~5분은 코드 상수 WIDE_SL_PCT(5%) 사용
-- TP 동일 (2.3%)
UPDATE morning_rush_config SET sl_pct = 3.0 WHERE id = 1;
