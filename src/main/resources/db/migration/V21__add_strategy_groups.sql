-- V21: Strategy Groups - 마켓 그룹별 전략/리스크 독립 설정
CREATE TABLE IF NOT EXISTS strategy_group (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  group_name VARCHAR(100) NOT NULL DEFAULT 'Group 1',
  sort_order INT NOT NULL DEFAULT 0,
  markets_csv VARCHAR(2048) NOT NULL DEFAULT '',
  strategy_types_csv VARCHAR(1024) NOT NULL DEFAULT '',
  candle_unit_min INT NOT NULL DEFAULT 60,
  order_sizing_mode VARCHAR(20) NOT NULL DEFAULT 'PCT',
  order_sizing_value DECIMAL(19,4) NOT NULL DEFAULT 90,
  take_profit_pct DECIMAL(10,4) NOT NULL DEFAULT 3.0,
  stop_loss_pct DECIMAL(10,4) NOT NULL DEFAULT 2.0,
  max_add_buys INT NOT NULL DEFAULT 2,
  strategy_lock TINYINT(1) NOT NULL DEFAULT 0,
  min_confidence DOUBLE NOT NULL DEFAULT 0,
  time_stop_minutes INT NOT NULL DEFAULT 0,
  strategy_intervals_csv VARCHAR(2048) NOT NULL DEFAULT '',
  ema_filter_csv VARCHAR(2048) NOT NULL DEFAULT ''
);
