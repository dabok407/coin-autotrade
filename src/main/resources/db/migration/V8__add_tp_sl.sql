-- Add global take-profit / stop-loss percent settings

ALTER TABLE bot_config ADD COLUMN IF NOT EXISTS take_profit_pct DECIMAL(10,4) NOT NULL DEFAULT 2.0000;
ALTER TABLE bot_config ADD COLUMN IF NOT EXISTS stop_loss_pct   DECIMAL(10,4) NOT NULL DEFAULT 3.0000;
