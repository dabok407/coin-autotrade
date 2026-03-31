-- V40: KITE SELL 매도가/PnL 보정
-- V38에서 매도가 333(매수가 동일)으로 잘못 기록 → 실제 매도가 330으로 수정
UPDATE trade_log
SET price = 330, pnl_krw = -271, roi_percent = -0.90
WHERE market = 'KRW-KITE' AND action = 'SELL' AND ts_epoch_ms = 1742518502000;
