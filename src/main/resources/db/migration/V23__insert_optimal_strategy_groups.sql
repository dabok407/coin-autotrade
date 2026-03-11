-- V23: Insert optimal strategy groups based on 90-day backtest optimization results
-- XRP: 3MKT+TRIANGLE 240m TP3%/SL2% → ROI +17.52%, WR 75%
-- SOL: 3MKT+TRIANGLE 240m TP7%/SL2% → ROI +7.29%, WR 53.85%
-- ADA: 3MKT 240m TP3%/SL2% → ROI +4.16%, WR 60%
-- BTC/ETH: excluded (low/negative ROI)

DELETE FROM strategy_group;

INSERT INTO strategy_group (group_name, sort_order, markets_csv, strategy_types_csv,
  candle_unit_min, order_sizing_mode, order_sizing_value,
  take_profit_pct, stop_loss_pct, max_add_buys,
  strategy_lock, min_confidence, time_stop_minutes,
  strategy_intervals_csv, ema_filter_csv)
VALUES
  ('XRP Optimal', 1, 'KRW-XRP',
   'THREE_MARKET_PATTERN,TRIANGLE_CONVERGENCE',
   240, 'PCT', 90, 3.0, 2.0, 2, 1, 1.0, 0, '', ''),

  ('SOL Optimal', 2, 'KRW-SOL',
   'THREE_MARKET_PATTERN,TRIANGLE_CONVERGENCE',
   240, 'PCT', 90, 7.0, 2.0, 2, 1, 1.0, 0, '', ''),

  ('ADA Optimal', 3, 'KRW-ADA',
   'THREE_MARKET_PATTERN',
   240, 'PCT', 90, 3.0, 2.0, 2, 1, 1.0, 0, '', '');
