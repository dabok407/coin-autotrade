-- V58: BERA 수동 매도 타임스탬프 수정 (2025→2026)
UPDATE trade_log
SET ts_epoch_ms = 1775296800000
WHERE market = 'KRW-BERA'
  AND action = 'SELL'
  AND ts_epoch_ms = 1743764400000;
