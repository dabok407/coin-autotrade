-- MR/Opening SL 소폭 강화 (실전 개시 직전 리스크 축소)
-- MR:      WIDE 3.5 → 2.8, TIGHT 3.0 → 2.4
-- Opening: WIDE 3.5 → 2.8, TIGHT 2.5 → 2.4 (TOP-N 전체 동일)

UPDATE morning_rush_config
   SET wide_sl_pct = 2.8,
       sl_pct      = 2.4
 WHERE id = 1;

UPDATE opening_scanner_config
   SET wide_sl_top10_pct = 2.8,
       wide_sl_top20_pct = 2.8,
       wide_sl_top50_pct = 2.8,
       wide_sl_other_pct = 2.8,
       tight_sl_pct      = 2.4
 WHERE id = 1;
