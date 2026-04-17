-- V119: 스캐너별 Split-Exit drop 값 차별화
-- 근거: Opening(폭발적 돌파, 타이트), MorningRush(지속 모멘텀, 현행 유지), AllDay(트렌드 추종, 관대)
-- 각 스캐너는 이미 독립 컬럼 보유. 값만 차별화.

-- Opening: 폭발적 돌파 → 반전 빠름 → 타이트
UPDATE opening_scanner_config
SET split_1st_trail_drop = 0.40,
    trail_drop_after_split = 1.00
WHERE id = 1;

-- MorningRush: 지속 모멘텀 → 현행 유지 (변경 없음, 명시성 위해 재확인 SET)
UPDATE morning_rush_config
SET split_1st_trail_drop = 0.50,
    trail_drop_after_split = 1.20
WHERE id = 1;

-- AllDay: 트렌드 추종 → 일시 조정 견디기 → 관대
UPDATE allday_scanner_config
SET split_1st_trail_drop = 0.70,
    trail_drop_after_split = 1.50
WHERE id = 1;
