-- V49: 오프닝 진입 종료 10:30 → 10:25
-- 10:30 캔들(체결 10:35)에 진입하면 12:00 세션 종료까지 시간 부족
-- 10:25 캔들(체결 10:30)을 마지막으로 하면 1시간 30분 보유 가능

UPDATE opening_scanner_config SET entry_end_hour = 10, entry_end_min = 25 WHERE id = 1;
