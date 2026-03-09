-- bot_config에 strategy_type 추가(기본값은 기존 전략)
ALTER TABLE bot_config ADD COLUMN strategy_type VARCHAR(64) NOT NULL DEFAULT 'CONSECUTIVE_DOWN_REBOUND';
