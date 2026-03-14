-- V27: Update strategy groups with optimization results (89.8M combinations, 2026-03-13)
-- Top performers per market:
--   SOL: INSIDE_BAR_BREAKOUT 240m TP15%/SL2% → ROI +84.42%, WR 41.2%, 17 trades
--   ADA: INSIDE_BAR_BREAKOUT 30m  TP20%/SL7% → ROI +65.20%, WR 80.0%, 5 trades
-- Sell-only strategies added for downside protection

DELETE FROM strategy_group;

INSERT INTO strategy_group (group_name, sort_order, markets_csv, strategy_types_csv,
  candle_unit_min, order_sizing_mode, order_sizing_value,
  take_profit_pct, stop_loss_pct, max_add_buys,
  strategy_lock, min_confidence, time_stop_minutes,
  strategy_intervals_csv, ema_filter_csv)
VALUES
  ('SOL Optimal', 1, 'KRW-SOL',
   'INSIDE_BAR_BREAKOUT,BULLISH_PINBAR_ORDERBLOCK,BEARISH_ENGULFING,EVENING_STAR_SELL,THREE_BLACK_CROWS_SELL',
   240, 'PCT', 90, 15.0, 3.0, 2, 0, 0.0, 2880,
   'BULLISH_PINBAR_ORDERBLOCK:60,BEARISH_ENGULFING:240,EVENING_STAR_SELL:240,THREE_BLACK_CROWS_SELL:240', ''),

  ('ADA Optimal', 2, 'KRW-ADA',
   'INSIDE_BAR_BREAKOUT,THREE_WHITE_SOLDIERS,BEARISH_ENGULFING,EVENING_STAR_SELL,THREE_BLACK_CROWS_SELL',
   30, 'PCT', 90, 20.0, 7.0, 1, 0, 7.0, 2880,
   'THREE_WHITE_SOLDIERS:5,BEARISH_ENGULFING:120,EVENING_STAR_SELL:120,THREE_BLACK_CROWS_SELL:120',
   'THREE_WHITE_SOLDIERS:50');

-- SOL, ADA만 활성화 확인
UPDATE market_config SET enabled = FALSE WHERE market NOT IN ('KRW-SOL', 'KRW-ADA');
UPDATE market_config SET enabled = TRUE  WHERE market IN ('KRW-SOL', 'KRW-ADA');
