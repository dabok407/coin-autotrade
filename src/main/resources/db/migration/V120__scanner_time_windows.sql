-- V120: 스캐너별 매수 가능 시간대 재조정
-- 목적: 09:00~24:00 사이 겹치지 않게 스캐너 매수 윈도우 배치
--   · MorningRush: 09:00 ~ 09:04:59  (5분)    — 기존 default 유지
--   · Opening:     09:05 ~ 10:04:59  (1시간)   — 기존 entry_end 10:29→10:04 축소
--   · AllDay:      10:05 ~ 23:59:59  (약 14시간) — entry_end 22:00→23:59 확장
-- 분 단위 해상도 + 부등호:
--   · MR: isEntryPhase = [entry_start, entry_end) → entry_end_min=5 이면 09:00~09:04 포함
--   · Opening: inEntry = [entry_start, entry_end] → entry_end=10:04 이면 09:05~10:04 포함
--   · AllDay:  inEntry = [entry_start, entry_end] → entry_end=23:59 이면 10:05~23:59 포함
--
-- AllDay session_end = 다음날 08:59 (overnight):
--   · entry_end=23:59와 동일할 경우 23:58 매수건이 23:59 캔들에서 HC_SESSION_END 즉시
--     강제청산되는 문제(1분 수명)를 회피하기 위해 세션 종료를 익일 08:59로 연장.
--   · HighConfidenceBreakoutStrategy.java:386 isOvernightSession 분기
--     (sessionEnd < 12:00 시 overnight) 활성 → 08:59~09:59 사이에만 청산, 10:00 이후
--     미청산(= 다음 거래일 시작).
--   · AllDayScannerService.java:492 inSession 로직도 overnight 분기 활성 →
--     00:00~09:29(08:59+30분 버퍼)에도 mainLoop tick 실행 → V118 peak/armed DB 영속화
--     루프가 24시간 중단 없이 돌아감.

-- MorningRush: 기본값과 동일하지만 명시성 위해 재확인 (변경 없음)
UPDATE morning_rush_config
SET range_start_hour = 8, range_start_min = 50,
    entry_start_hour = 9, entry_start_min = 0,
    entry_end_hour = 9,   entry_end_min = 5,
    session_end_hour = 10, session_end_min = 0
WHERE id = 1;

-- Opening: entry_end 10:29 → 10:04 (10:05부터 AllDay로 바톤)
UPDATE opening_scanner_config
SET entry_start_hour = 9,  entry_start_min = 5,
    entry_end_hour = 10,   entry_end_min = 4,
    session_end_hour = 12, session_end_min = 0
WHERE id = 1;

-- AllDay: entry 10:30→10:05, entry_end 22:00→23:59, session_end 23:00→익일 08:59 (overnight)
UPDATE allday_scanner_config
SET entry_start_hour = 10, entry_start_min = 5,
    entry_end_hour = 23,   entry_end_min = 59,
    session_end_hour = 8,  session_end_min = 59
WHERE id = 1;
