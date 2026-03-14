-- 백테스트 최적화 결과 저장 테이블
CREATE TABLE IF NOT EXISTS optimization_result (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id           VARCHAR(50)  NOT NULL,
    strategy_type    VARCHAR(50)  NOT NULL,
    market           VARCHAR(20)  NOT NULL,
    interval_min     INT          NOT NULL,
    tp_pct           DOUBLE       NOT NULL,
    sl_pct           DOUBLE       NOT NULL,
    max_add_buys     INT          NOT NULL DEFAULT 2,
    min_confidence   DOUBLE       NOT NULL DEFAULT 0,
    strategy_lock    BOOLEAN      NOT NULL DEFAULT FALSE,
    time_stop_minutes INT         NOT NULL DEFAULT 0,
    ema_period       INT          NOT NULL DEFAULT 0,
    roi              DOUBLE       NOT NULL,
    win_rate         DOUBLE       NOT NULL,
    total_trades     INT          NOT NULL,
    wins             INT          NOT NULL DEFAULT 0,
    total_pnl        DOUBLE       NOT NULL DEFAULT 0,
    final_capital    DOUBLE       NOT NULL DEFAULT 0,
    tp_sell_count    INT          NOT NULL DEFAULT 0,
    sl_sell_count    INT          NOT NULL DEFAULT 0,
    pattern_sell_count INT        NOT NULL DEFAULT 0,
    created_at       TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_opt_run ON optimization_result (run_id);
CREATE INDEX IF NOT EXISTS idx_opt_ranking ON optimization_result (market, roi DESC);
