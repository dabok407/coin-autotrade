-- 자본 50M + 포지션 전체 정리
UPDATE bot_config SET capital_krw = 50000000 WHERE id = 1;
DELETE FROM position WHERE entry_strategy IN ('SCALP_OPENING_BREAK', 'HIGH_CONFIDENCE_BREAKOUT', 'MORNING_RUSH');
