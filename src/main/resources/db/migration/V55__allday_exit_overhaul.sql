-- V55: 올데이 스캐너 청산 로직 대폭 개선
--
-- 문제: SL 1.5% 너무 타이트 (throwback 손절), 트레일링 0.8xATR 노이즈 청산,
--       EMA/MACD 청산 과민, Time Stop 60분 조기 포기, BTC 필터 기회 차단
--
-- 변경 내역:
-- 1. Hard SL: 1.5% → 3.0% (돌파 후 throwback 2~3% 허용)
-- 2. 트레일링: 0.8 → 2.5 × ATR (노이즈 방어), 활성화 1.5%부터
-- 3. Grace Period: 진입 후 6캔들(30분) 보호 (Hard SL만 작동)
-- 4. MACD Fade 청산: 비활성화 (5분봉 MACD 음전환 과빈)
-- 5. EMA Break 청산: 비활성화 (5분봉 EMA 교차 노이즈)
-- 6. Time Stop: 비활성화 (0 = OFF, 트레일링+SL에 맡김)
-- 7. BTC 필터: C안 (코드에서 per-signal 바이패스, DB 변경 불필요)
-- 8. max_positions: 2 → 5, PCT 유지 20% (분산 투자)

-- 신규 컬럼 추가
ALTER TABLE allday_scanner_config ADD COLUMN trail_activate_pct DOUBLE NOT NULL DEFAULT 0.5;
ALTER TABLE allday_scanner_config ADD COLUMN grace_period_candles INT NOT NULL DEFAULT 0;
ALTER TABLE allday_scanner_config ADD COLUMN ema_exit_enabled BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE allday_scanner_config ADD COLUMN macd_exit_enabled BOOLEAN NOT NULL DEFAULT TRUE;

-- 값 설정
UPDATE allday_scanner_config SET
    sl_pct = 3.0,
    trail_atr_mult = 2.5,
    trail_activate_pct = 1.5,
    grace_period_candles = 6,
    ema_exit_enabled = false,
    macd_exit_enabled = false,
    time_stop_candles = 0,
    max_positions = 5
WHERE id = 1;
