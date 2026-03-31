ALTER TABLE allday_scanner_config ADD COLUMN quick_tp_enabled BOOLEAN DEFAULT TRUE;
ALTER TABLE allday_scanner_config ADD COLUMN quick_tp_pct DECIMAL(5,2) DEFAULT 0.70;
ALTER TABLE allday_scanner_config ADD COLUMN quick_tp_interval_sec INT DEFAULT 5;
