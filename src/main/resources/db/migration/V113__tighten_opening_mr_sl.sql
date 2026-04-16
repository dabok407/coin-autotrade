-- 오프닝/모닝러쉬 SL 강화: SL_WIDE 6.0→3.5%, SL_TIGHT 3.0→2.5%
UPDATE opening_scanner_config
   SET wide_sl_top10_pct = 3.5,
       wide_sl_top20_pct = 3.5,
       wide_sl_top50_pct = 3.5,
       wide_sl_other_pct = 3.5,
       tight_sl_pct      = 2.5
 WHERE id = 1;

UPDATE morning_rush_config
   SET wide_sl_pct = 3.5
 WHERE id = 1;
