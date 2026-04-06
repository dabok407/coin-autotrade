-- V59: quick_tp_pct를 WebSocket 실시간 TP 기준으로 변경 (0.70 → 2.10)
-- Quick TP 폴링 방식은 WebSocket TP로 대체됨
UPDATE allday_scanner_config SET quick_tp_pct = 2.10 WHERE id = 1;
