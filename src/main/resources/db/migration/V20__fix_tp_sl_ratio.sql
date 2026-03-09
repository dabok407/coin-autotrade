-- Fix inverted TP/SL ratio: old defaults TP=2%/SL=3% (R:R=0.67) -> new TP=4%/SL=2% (R:R=2.0)
-- Only update rows that still have the original defaults (don't override user customizations)
UPDATE bot_config SET take_profit_pct = 4.0000, stop_loss_pct = 2.0000
WHERE take_profit_pct = 2.0000 AND stop_loss_pct = 3.0000;
