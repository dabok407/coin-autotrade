-- 스캐너 매수 윈도우 정렬 (모닝러쉬 → 오프닝 → 올데이 빈틈없이 연속)
-- 변경 전:
--   모닝러쉬: 09:00~09:05  (코드 하드코딩)
--   오프닝:   09:00~10:25  ← 모닝러쉬와 09:00~09:05 5분 중복 (TREE 사고 발생)
--   올데이:   10:35~22:00  ← 오프닝과 5분 갭
-- 변경 후:
--   모닝러쉬: 09:00~09:05  (DB 컬럼화)
--   오프닝:   09:05~10:29
--   올데이:   10:30~22:00

-- 1) 오프닝: 진입 시작 09:00 → 09:05, 종료 10:25 → 10:29
UPDATE opening_scanner_config
SET entry_start_min = 5,
    entry_end_min   = 29
WHERE id = 1;

-- 2) 올데이: 진입 시작 10:35 → 10:30
UPDATE allday_scanner_config
SET entry_start_min = 30
WHERE id = 1;

-- 3) 모닝러쉬: 페이즈 타이밍 컬럼 추가 (코드 하드코딩 제거)
ALTER TABLE morning_rush_config ADD COLUMN range_start_hour INT NOT NULL DEFAULT 8;
ALTER TABLE morning_rush_config ADD COLUMN range_start_min  INT NOT NULL DEFAULT 50;
ALTER TABLE morning_rush_config ADD COLUMN entry_start_hour INT NOT NULL DEFAULT 9;
ALTER TABLE morning_rush_config ADD COLUMN entry_start_min  INT NOT NULL DEFAULT 0;
ALTER TABLE morning_rush_config ADD COLUMN entry_end_hour   INT NOT NULL DEFAULT 9;
ALTER TABLE morning_rush_config ADD COLUMN entry_end_min    INT NOT NULL DEFAULT 5;

-- 기존 행에도 명시적으로 값 설정 (DEFAULT 미반영 환경 대비)
UPDATE morning_rush_config
SET range_start_hour = 8, range_start_min = 50,
    entry_start_hour = 9, entry_start_min = 0,
    entry_end_hour   = 9, entry_end_min   = 5
WHERE id = 1;

-- 검증
SELECT 'opening' AS scanner, entry_start_hour AS sh, entry_start_min AS sm,
       entry_end_hour AS eh, entry_end_min AS em
FROM opening_scanner_config WHERE id = 1
UNION ALL
SELECT 'allday', entry_start_hour, entry_start_min, entry_end_hour, entry_end_min
FROM allday_scanner_config WHERE id = 1
UNION ALL
SELECT 'morning_rush', entry_start_hour, entry_start_min, entry_end_hour, entry_end_min
FROM morning_rush_config WHERE id = 1;
