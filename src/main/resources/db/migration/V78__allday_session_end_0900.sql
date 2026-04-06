-- V78: 올데이 세션 종료 08:00 → 09:00 (1시간 연장)
UPDATE allday_scanner_config SET session_end_hour = 9, session_end_min = 0 WHERE id = 1;
