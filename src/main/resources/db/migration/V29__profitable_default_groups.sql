-- V29: Update default groups to verified profitable settings (PRACTICAL_GUIDE.md analysis)
-- Based on 758 backtests across all market conditions:
--   SOL BULL_AGG: 3M상승장 +9.36%, 3M횡보장 +7.30% (TP3/SL1, ERT+BE+ESS+TBCS)
--   ADA BEAR_STB: 3M상승장 +4.73%, 1M상승장 +3.97% (TP1.5/SL0.5, ERT+BE+ESS+TBCS)
-- Note: SCALP_MOMENTUM deprecated, using EMA_RSI_TREND as sole buy strategy

DELETE FROM strategy_group;

INSERT INTO strategy_group (group_name, sort_order, markets_csv, strategy_types_csv,
  candle_unit_min, order_sizing_mode, order_sizing_value,
  take_profit_pct, stop_loss_pct, max_add_buys,
  strategy_lock, min_confidence, time_stop_minutes,
  strategy_intervals_csv, ema_filter_csv)
VALUES
  ('SOL Profitable', 1, 'KRW-SOL',
   'EMA_RSI_TREND,BEARISH_ENGULFING,EVENING_STAR_SELL,THREE_BLACK_CROWS_SELL',
   240, 'PCT', 90, 3.0, 1.0, 2, 0, 7.0, 480,
   'BEARISH_ENGULFING:240,EVENING_STAR_SELL:240,THREE_BLACK_CROWS_SELL:240', ''),

  ('ADA Conservative', 2, 'KRW-ADA',
   'EMA_RSI_TREND,BEARISH_ENGULFING,EVENING_STAR_SELL,THREE_BLACK_CROWS_SELL',
   240, 'PCT', 90, 1.5, 0.5, 0, 0, 9.0, 240,
   'BEARISH_ENGULFING:240,EVENING_STAR_SELL:240,THREE_BLACK_CROWS_SELL:240', '');

UPDATE market_config SET enabled = FALSE WHERE market NOT IN ('KRW-SOL', 'KRW-ADA');
UPDATE market_config SET enabled = TRUE  WHERE market IN ('KRW-SOL', 'KRW-ADA');
