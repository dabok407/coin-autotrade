-- Opening Scanner: 독립 오프닝 레인지 돌파 스캐너 설정 테이블
CREATE TABLE opening_scanner_config (
    id                  INT NOT NULL PRIMARY KEY,
    enabled             BOOLEAN DEFAULT FALSE,
    mode                VARCHAR(10) DEFAULT 'PAPER',
    top_n               INT DEFAULT 15,
    max_positions       INT DEFAULT 3,
    capital_krw         DECIMAL(20,2) DEFAULT 100000,
    order_sizing_mode   VARCHAR(10) DEFAULT 'PCT',
    order_sizing_value  DECIMAL(10,2) DEFAULT 30,
    candle_unit_min     INT DEFAULT 5,
    -- 타이밍 파라미터
    range_start_hour    INT DEFAULT 8,
    range_start_min     INT DEFAULT 0,
    range_end_hour      INT DEFAULT 8,
    range_end_min       INT DEFAULT 59,
    entry_start_hour    INT DEFAULT 9,
    entry_start_min     INT DEFAULT 5,
    entry_end_hour      INT DEFAULT 10,
    entry_end_min       INT DEFAULT 30,
    session_end_hour    INT DEFAULT 12,
    session_end_min     INT DEFAULT 0,
    -- 리스크 파라미터
    tp_atr_mult         DECIMAL(5,2) DEFAULT 1.2,
    sl_pct              DECIMAL(5,2) DEFAULT 10.0,
    trail_atr_mult      DECIMAL(5,2) DEFAULT 0.8,
    -- 필터
    btc_filter_enabled  BOOLEAN DEFAULT TRUE,
    btc_ema_period      INT DEFAULT 20,
    volume_mult         DECIMAL(5,2) DEFAULT 1.5,
    min_body_ratio      DECIMAL(5,2) DEFAULT 0.40
);

INSERT INTO opening_scanner_config (id) VALUES (1);
