CREATE TABLE allday_scanner_config (
    id                  INT NOT NULL PRIMARY KEY,
    enabled             BOOLEAN DEFAULT FALSE,
    mode                VARCHAR(10) DEFAULT 'PAPER',
    top_n               INT DEFAULT 15,
    max_positions       INT DEFAULT 2,
    order_sizing_mode   VARCHAR(10) DEFAULT 'PCT',
    order_sizing_value  DECIMAL(10,2) DEFAULT 20,
    candle_unit_min     INT DEFAULT 5,
    entry_start_hour    INT DEFAULT 10,
    entry_start_min     INT DEFAULT 35,
    entry_end_hour      INT DEFAULT 7,
    entry_end_min       INT DEFAULT 30,
    session_end_hour    INT DEFAULT 8,
    session_end_min     INT DEFAULT 0,
    sl_pct              DECIMAL(5,2) DEFAULT 1.5,
    trail_atr_mult      DECIMAL(5,2) DEFAULT 0.8,
    min_confidence      DECIMAL(5,2) DEFAULT 9.4,
    time_stop_candles   INT DEFAULT 12,
    time_stop_min_pnl   DECIMAL(5,2) DEFAULT 0.3,
    btc_filter_enabled  BOOLEAN DEFAULT TRUE,
    btc_ema_period      INT DEFAULT 20,
    volume_surge_mult   DECIMAL(5,2) DEFAULT 3.0,
    min_body_ratio      DECIMAL(5,2) DEFAULT 0.60,
    exclude_markets     VARCHAR(1000) DEFAULT ''
);

INSERT INTO allday_scanner_config (id) VALUES (1);
