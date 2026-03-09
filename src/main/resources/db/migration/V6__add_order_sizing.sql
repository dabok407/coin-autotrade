-- Add global order sizing config
ALTER TABLE bot_config ADD COLUMN IF NOT EXISTS order_sizing_mode VARCHAR(16) NOT NULL DEFAULT 'FIXED';
ALTER TABLE bot_config ADD COLUMN IF NOT EXISTS order_sizing_value DECIMAL(19,4) NOT NULL DEFAULT 10000;

UPDATE bot_config
SET order_sizing_mode = COALESCE(NULLIF(order_sizing_mode, ''), 'FIXED'),
    order_sizing_value = CASE
        WHEN order_sizing_value IS NULL OR order_sizing_value <= 0 THEN 10000
        ELSE order_sizing_value
    END;
