-- V57: BERA 수동 매도 반영 (2026-04-04 19:00 KST)
-- 매수 655.0 x 183.20610687 → 수동 매도 702.0
-- PnL: +8,546 KRW, ROI: +7.18%

-- 1. 매도 거래 로그 추가
INSERT INTO trade_log (ts_epoch_ms, market, action, price, qty, pnl_krw, roi_percent,
                       mode, pattern_type, pattern_reason, avg_buy_price, candle_unit_min)
VALUES (1743764400000, 'KRW-BERA', 'SELL', 702.0, 183.20610687,
        8546, 7.18, 'LIVE', 'HIGH_CONFIDENCE_BREAKOUT',
        'MANUAL_SELL 수동매도 +7.18% (WS_TP 배포 전)', 655.0, 5);

-- 2. 포지션 삭제
DELETE FROM position WHERE market = 'KRW-BERA';
