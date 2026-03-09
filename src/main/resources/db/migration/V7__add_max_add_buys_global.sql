-- Add global max add-buys limit (applies to all markets)
ALTER TABLE bot_config ADD COLUMN IF NOT EXISTS max_add_buys_global INT NOT NULL DEFAULT 2;

-- Ensure existing rows have sane default
UPDATE bot_config SET max_add_buys_global = 2 WHERE max_add_buys_global IS NULL OR max_add_buys_global < 0;
