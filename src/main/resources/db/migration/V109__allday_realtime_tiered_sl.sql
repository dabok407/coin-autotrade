-- 올데이 스캐너: 실시간 티어드 SL + TP_TRAIL DB화 (2026-04-13)
-- 매도 구조 재설계: checkRealtimeTp → checkRealtimeTpSl 통합
-- 진입 필터 강화: RSI/Vol/EMA 조건 추가

-- 1. 티어드 SL 컬럼 (모닝러쉬 패턴 동일)
ALTER TABLE allday_scanner_config ADD COLUMN IF NOT EXISTS grace_period_sec INT DEFAULT 30;
ALTER TABLE allday_scanner_config ADD COLUMN IF NOT EXISTS wide_period_min INT DEFAULT 15;
ALTER TABLE allday_scanner_config ADD COLUMN IF NOT EXISTS wide_sl_pct DECIMAL(5,2) DEFAULT 3.0;

-- 2. TP_TRAIL DB화 (하드코딩 제거)
ALTER TABLE allday_scanner_config ADD COLUMN IF NOT EXISTS tp_trail_activate_pct DECIMAL(5,2) DEFAULT 2.0;
ALTER TABLE allday_scanner_config ADD COLUMN IF NOT EXISTS tp_trail_drop_pct DECIMAL(5,2) DEFAULT 1.0;

-- 3. 진입 필터: RSI 상한
ALTER TABLE allday_scanner_config ADD COLUMN IF NOT EXISTS max_entry_rsi INT DEFAULT 80;

-- 4. 진입 필터: 최소 거래량 배수 (HCB hard gate 강화)
ALTER TABLE allday_scanner_config ADD COLUMN IF NOT EXISTS min_volume_mult DECIMAL(5,2) DEFAULT 5.0;

-- 5. 설정 적용
UPDATE allday_scanner_config SET
  grace_period_sec = 30,
  wide_period_min = 15,
  wide_sl_pct = 3.0,
  tp_trail_activate_pct = 2.0,
  tp_trail_drop_pct = 1.0,
  max_entry_rsi = 80,
  min_volume_mult = 5.0,
  ema_exit_enabled = FALSE,
  macd_exit_enabled = FALSE
WHERE id = 1;
