-- 모닝러쉬 SESSION_END 10:00 → 11:30 연장
-- 90일 백테스트 분석: 흔들기 → 반등까지 평균 37.8분 소요
-- 10:00 청산은 흔들기 회복 시간 부족 → 11:30으로 연장
-- (운용 시간 ~2.5시간, 모닝러쉬 단기 컨셉 유지)
UPDATE morning_rush_config SET session_end_hour = 11, session_end_min = 30 WHERE id = 1;
