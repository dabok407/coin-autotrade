-- V51: 오프닝 진입 시작 09:05 → 09:00
-- 09:00 캔들 완성(09:05)에 바로 돌파 체크 → 5분 빠른 진입
UPDATE opening_scanner_config SET entry_start_hour = 9, entry_start_min = 0 WHERE id = 1;
