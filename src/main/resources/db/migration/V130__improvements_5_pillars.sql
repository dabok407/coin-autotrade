-- V130: 5개 개선안 일괄 도입
-- ① Trail Ladder A — peak% 구간별 차등 drop (split_1st × 4 + trail_after × 4) × 3 스캐너
-- ② L1 60s 지연 진입 — 1분봉 close >= 진입가 확인 후 매수 (3 스캐너)
-- ③ 스캐너 간 동일종목 재진입 차단 — 글로벌 락 (bot_config)
-- ④ SPLIT_1ST roi 하한선 — 손실/원가 상태 1차매도 차단 (3 스캐너)
-- ⑤ Dust 보유 제외 — 평가금액 < 임계 미만은 보유로 간주 안 함 (글로벌, bot_config)
--
-- 근거: 8일(2026-04-20~04-27) 1분봉 실가격 백테스트
--   V129 baseline: WR 53%, PnL -12,951
--   ① + ② 조합: WR 76%, PnL +59,823 (변화 +72,774)
--
-- 기존 컬럼 (split_1st_trail_drop, trail_drop_after_split) 유지: 호환성/롤백용 + 기본값 fallback

-- ============================================================
-- ① Trail Ladder A — 3 스캐너 × (split_1st 4구간 + trail_after 4구간) = 24 컬럼
-- H2 호환: ALTER TABLE ... ADD COLUMN 문장당 1개 컬럼
-- ============================================================

-- MR: split_1st ladder (4구간)
ALTER TABLE morning_rush_config ADD COLUMN split_1st_drop_under_2 DECIMAL(5,2) NOT NULL DEFAULT 0.50;
ALTER TABLE morning_rush_config ADD COLUMN split_1st_drop_under_3 DECIMAL(5,2) NOT NULL DEFAULT 1.00;
ALTER TABLE morning_rush_config ADD COLUMN split_1st_drop_under_5 DECIMAL(5,2) NOT NULL DEFAULT 1.50;
ALTER TABLE morning_rush_config ADD COLUMN split_1st_drop_above_5 DECIMAL(5,2) NOT NULL DEFAULT 2.00;

-- MR: trail_after ladder (4구간)
ALTER TABLE morning_rush_config ADD COLUMN trail_after_drop_under_2 DECIMAL(5,2) NOT NULL DEFAULT 1.00;
ALTER TABLE morning_rush_config ADD COLUMN trail_after_drop_under_3 DECIMAL(5,2) NOT NULL DEFAULT 1.20;
ALTER TABLE morning_rush_config ADD COLUMN trail_after_drop_under_5 DECIMAL(5,2) NOT NULL DEFAULT 1.50;
ALTER TABLE morning_rush_config ADD COLUMN trail_after_drop_above_5 DECIMAL(5,2) NOT NULL DEFAULT 2.00;

-- OP: split_1st ladder (4구간)
ALTER TABLE opening_scanner_config ADD COLUMN split_1st_drop_under_2 DECIMAL(5,2) NOT NULL DEFAULT 0.50;
ALTER TABLE opening_scanner_config ADD COLUMN split_1st_drop_under_3 DECIMAL(5,2) NOT NULL DEFAULT 1.00;
ALTER TABLE opening_scanner_config ADD COLUMN split_1st_drop_under_5 DECIMAL(5,2) NOT NULL DEFAULT 1.50;
ALTER TABLE opening_scanner_config ADD COLUMN split_1st_drop_above_5 DECIMAL(5,2) NOT NULL DEFAULT 2.00;

-- OP: trail_after ladder (4구간)
ALTER TABLE opening_scanner_config ADD COLUMN trail_after_drop_under_2 DECIMAL(5,2) NOT NULL DEFAULT 1.00;
ALTER TABLE opening_scanner_config ADD COLUMN trail_after_drop_under_3 DECIMAL(5,2) NOT NULL DEFAULT 1.20;
ALTER TABLE opening_scanner_config ADD COLUMN trail_after_drop_under_5 DECIMAL(5,2) NOT NULL DEFAULT 1.50;
ALTER TABLE opening_scanner_config ADD COLUMN trail_after_drop_above_5 DECIMAL(5,2) NOT NULL DEFAULT 2.00;

-- AD: split_1st ladder (4구간)
ALTER TABLE allday_scanner_config ADD COLUMN split_1st_drop_under_2 DECIMAL(5,2) NOT NULL DEFAULT 0.50;
ALTER TABLE allday_scanner_config ADD COLUMN split_1st_drop_under_3 DECIMAL(5,2) NOT NULL DEFAULT 1.00;
ALTER TABLE allday_scanner_config ADD COLUMN split_1st_drop_under_5 DECIMAL(5,2) NOT NULL DEFAULT 1.50;
ALTER TABLE allday_scanner_config ADD COLUMN split_1st_drop_above_5 DECIMAL(5,2) NOT NULL DEFAULT 2.00;

-- AD: trail_after ladder (4구간)
ALTER TABLE allday_scanner_config ADD COLUMN trail_after_drop_under_2 DECIMAL(5,2) NOT NULL DEFAULT 1.00;
ALTER TABLE allday_scanner_config ADD COLUMN trail_after_drop_under_3 DECIMAL(5,2) NOT NULL DEFAULT 1.20;
ALTER TABLE allday_scanner_config ADD COLUMN trail_after_drop_under_5 DECIMAL(5,2) NOT NULL DEFAULT 1.50;
ALTER TABLE allday_scanner_config ADD COLUMN trail_after_drop_above_5 DECIMAL(5,2) NOT NULL DEFAULT 2.00;

-- Trail Ladder enabled flag
ALTER TABLE morning_rush_config ADD COLUMN trail_ladder_enabled BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE opening_scanner_config ADD COLUMN trail_ladder_enabled BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE allday_scanner_config ADD COLUMN trail_ladder_enabled BOOLEAN NOT NULL DEFAULT TRUE;

-- ============================================================
-- ② L1 60s 지연 진입 — 시그널 후 N초 대기, close >= 진입가 확인 후 매수
-- 0이면 즉시 진입 (V129 동작), 60이 권장
-- ============================================================
ALTER TABLE morning_rush_config ADD COLUMN l1_delay_sec INT NOT NULL DEFAULT 60;
ALTER TABLE opening_scanner_config ADD COLUMN l1_delay_sec INT NOT NULL DEFAULT 60;
ALTER TABLE allday_scanner_config ADD COLUMN l1_delay_sec INT NOT NULL DEFAULT 60;

-- ============================================================
-- ③ 스캐너 간 동일종목 재진입 차단 — 글로벌 락 (bot_config 활용)
-- ============================================================
ALTER TABLE bot_config ADD COLUMN cross_scanner_lock_enabled BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE bot_config ADD COLUMN same_market_loss_cooldown_min INT NOT NULL DEFAULT 360;

-- ============================================================
-- ④ SPLIT_1ST roi 하한선
-- ============================================================
ALTER TABLE morning_rush_config ADD COLUMN split_1st_roi_floor_pct DECIMAL(5,2) NOT NULL DEFAULT 0.30;
ALTER TABLE opening_scanner_config ADD COLUMN split_1st_roi_floor_pct DECIMAL(5,2) NOT NULL DEFAULT 0.30;
ALTER TABLE allday_scanner_config ADD COLUMN split_1st_roi_floor_pct DECIMAL(5,2) NOT NULL DEFAULT 0.30;

-- ============================================================
-- ⑤ Dust 보유 판단 제외
-- ============================================================
ALTER TABLE bot_config ADD COLUMN dust_holding_threshold_krw INT NOT NULL DEFAULT 5000;
