-- 전략별 캔들 인터벌 오버라이드 (CSV: "TYPE:MIN,TYPE:MIN,...")
-- 비어있으면 모든 전략이 글로벌 candle_unit_min 사용 (기존 동작 유지)
ALTER TABLE bot_config ADD COLUMN strategy_intervals_csv VARCHAR(2048) NOT NULL DEFAULT '';
