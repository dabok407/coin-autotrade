-- V33: Add ETH and NEAR strategy groups with per-coin optimized settings
-- Optimization: 840 single-strategy combos + 30 multi-strategy combos (365d backtest)
--
-- ETH Optimal: EMA_RSI_TREND + INSIDE_BAR_BREAKOUT (buy) + 3 sell strategies
--   240min, TP7%/SL3%, conf>=7, 365d ROI +25.87%, 77 trades, WR 13%
--   - ERT alone: +22.27% (54t), IBB alone: +19.99% (33t) → combo is synergistic
--   - 60min/120min all negative for ETH → 240min only
--
-- NEAR Optimal: BULLISH_PINBAR_ORDERBLOCK + EMA_RSI_TREND + INSIDE_BAR_BREAKOUT (buy) + 3 sell
--   240min, TP7%/SL3%, conf>=7, 365d ROI +62.39%, 116 trades, WR 15.5%
--   - ERT alone: +38.57% (36t), BPO alone: +12.89% (70t), IBB alone: +8.87% (20t)
--   - 60min/120min deeply negative for NEAR → 240min only
--   - Triple combo outperforms any pair (BPO+ERT: +54.48%, ERT+IBB: +36.49%)

-- market_config: add ETH and NEAR if not exists
INSERT INTO market_config (market, enabled, base_order_krw)
SELECT 'KRW-ETH', TRUE, 10000
WHERE NOT EXISTS (SELECT 1 FROM market_config WHERE market = 'KRW-ETH');

INSERT INTO market_config (market, enabled, base_order_krw)
SELECT 'KRW-NEAR', TRUE, 10000
WHERE NOT EXISTS (SELECT 1 FROM market_config WHERE market = 'KRW-NEAR');

-- If ETH/NEAR already exist, just enable them
UPDATE market_config SET enabled = TRUE WHERE market = 'KRW-ETH';
UPDATE market_config SET enabled = TRUE WHERE market = 'KRW-NEAR';

-- Add strategy groups (keep existing SOL/ADA groups intact)
INSERT INTO strategy_group (group_name, sort_order, markets_csv, strategy_types_csv,
  candle_unit_min, order_sizing_mode, order_sizing_value,
  take_profit_pct, stop_loss_pct, max_add_buys,
  strategy_lock, min_confidence, time_stop_minutes,
  strategy_intervals_csv, ema_filter_csv)
VALUES
  ('ETH Momentum', 3, 'KRW-ETH',
   'EMA_RSI_TREND,INSIDE_BAR_BREAKOUT,BEARISH_ENGULFING,EVENING_STAR_SELL,THREE_BLACK_CROWS_SELL',
   240, 'PCT', 90, 7.0, 3.0, 2, 0, 7.0, 480,
   'BEARISH_ENGULFING:240,EVENING_STAR_SELL:240,THREE_BLACK_CROWS_SELL:240', ''),

  ('NEAR Aggressive', 4, 'KRW-NEAR',
   'BULLISH_PINBAR_ORDERBLOCK,EMA_RSI_TREND,INSIDE_BAR_BREAKOUT,BEARISH_ENGULFING,EVENING_STAR_SELL,THREE_BLACK_CROWS_SELL',
   240, 'PCT', 90, 7.0, 3.0, 2, 0, 7.0, 480,
   'BEARISH_ENGULFING:240,EVENING_STAR_SELL:240,THREE_BLACK_CROWS_SELL:240', '');
