-- Add ts_epoch_ms column to order_log for fast ordering/lookup and to match JPA mapping
ALTER TABLE order_log
  ADD COLUMN ts_epoch_ms BIGINT NOT NULL DEFAULT 0 AFTER error_message;

CREATE INDEX idx_order_ts ON order_log (ts_epoch_ms);
