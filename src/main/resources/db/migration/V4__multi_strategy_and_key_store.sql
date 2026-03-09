-- Multi-strategy support: store selected strategy types as CSV
-- IMPORTANT: must be compatible with both H2 and MySQL.
-- We add the column with a DEFAULT to avoid NOT NULL migration failures when rows already exist.
ALTER TABLE bot_config
    ADD COLUMN strategy_types_csv VARCHAR(255) NOT NULL DEFAULT '';

-- Backfill: if old single strategy_type exists, copy it into the csv column (only when empty).
UPDATE bot_config
SET strategy_types_csv =
    CASE
        WHEN strategy_types_csv IS NULL OR strategy_types_csv = ''
        THEN COALESCE(strategy_type, '')
        ELSE strategy_types_csv
    END;

-- migrate existing single strategy_type into csv if csv empty
UPDATE bot_config
SET strategy_types_csv = strategy_type
WHERE (strategy_types_csv IS NULL OR strategy_types_csv = '') AND (strategy_type IS NOT NULL AND strategy_type <> '');

-- 2) Upbit API key store (encrypted)
CREATE TABLE IF NOT EXISTS api_key_store (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  provider VARCHAR(32) NOT NULL,
  access_key_enc BLOB NOT NULL,
  secret_key_enc BLOB NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_provider (provider)
);
