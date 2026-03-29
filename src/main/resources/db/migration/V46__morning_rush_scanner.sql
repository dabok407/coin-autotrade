-- Morning Rush Scanner config (singleton row, id=1)
CREATE TABLE morning_rush_config (
    id INT AUTO_INCREMENT PRIMARY KEY,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    mode VARCHAR(10) NOT NULL DEFAULT 'PAPER',
    top_n INT NOT NULL DEFAULT 30,
    max_positions INT NOT NULL DEFAULT 2,
    order_sizing_mode VARCHAR(10) NOT NULL DEFAULT 'PCT',
    order_sizing_value DECIMAL(10,2) NOT NULL DEFAULT 20,
    gap_threshold_pct DECIMAL(5,2) NOT NULL DEFAULT 5.0,
    volume_mult DECIMAL(5,2) NOT NULL DEFAULT 5.0,
    confirm_count INT NOT NULL DEFAULT 3,
    check_interval_sec INT NOT NULL DEFAULT 5,
    tp_pct DECIMAL(5,2) NOT NULL DEFAULT 2.0,
    sl_pct DECIMAL(5,2) NOT NULL DEFAULT 3.0,
    session_end_hour INT NOT NULL DEFAULT 10,
    session_end_min INT NOT NULL DEFAULT 0,
    min_trade_amount BIGINT NOT NULL DEFAULT 1000000000,
    exclude_markets VARCHAR(1000) DEFAULT '',
    min_price_krw INT NOT NULL DEFAULT 20
);
INSERT INTO morning_rush_config (id) VALUES (1);
