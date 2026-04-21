-- V129: Split-Exit v2 — 오늘(2026-04-21) AXL/FLOCK/BIO 실거래 분석 기반 개선
--
-- 배경:
--   AXL/FLOCK 매수 직후 꼬리(-2~3%) 후 대폭 반등 — 현 파라미터로는 꼬리 구간에서
--   SPLIT_1ST armed 유발 → drop 트리거 → 1차 매도 → BEV 조건으로 2차 본전 매도 → 반등 놓침.
--
-- 변경 5가지:
--   (1) split_1st_trail_drop 모두 2.00% 통일 (MR 0.65, OP 0.45, AD 0.85 → 2.00)
--   (2) trail_drop_after_split 모두 2.00% 통일 (MR 1.40, OP 1.20, AD 1.20 → 2.00)
--   (3) AllDay grace_period_sec 30 → 60 (MR/OP와 통일)
--   (4) MR/AllDay에 split_1st_cooldown_sec 컬럼 추가 (Opening은 V126에서 이미 보유)
--   (5) SPLIT_2ND_BEV 조건 제거는 Java 코드 변경이므로 SQL 무관
--
-- 쿨다운 정책 (MR/OP/AD 공통):
--   - SPLIT_1ST 체결 후 60초 동안 SPLIT_2ND_TRAIL 차단
--   - SL은 허용 (진짜 급락 방어)
--   - Grace는 SPLIT 포함 모든 매도 차단 (별도 로직, 시간대 다름)
--
-- 롤백:
--   ALTER TABLE ... DROP COLUMN split_1st_cooldown_sec  (MR/AD)
--   UPDATE ... SET split_1st_trail_drop=구값, trail_drop_after_split=구값
--   UPDATE allday_scanner_config SET grace_period_sec=30

-- ============================================================
-- (1) 1차/2차 drop 모두 2.0%로 통일
-- ============================================================
UPDATE morning_rush_config
SET split_1st_trail_drop = 2.00,
    trail_drop_after_split = 2.00
WHERE id = 1;

UPDATE opening_scanner_config
SET split_1st_trail_drop = 2.00,
    trail_drop_after_split = 2.00
WHERE id = 1;

UPDATE allday_scanner_config
SET split_1st_trail_drop = 2.00,
    trail_drop_after_split = 2.00
WHERE id = 1;

-- ============================================================
-- (2) AllDay grace_period_sec 30 → 60 (MR/OP와 통일)
-- ============================================================
UPDATE allday_scanner_config
SET grace_period_sec = 60
WHERE id = 1;

-- ============================================================
-- (3) MR/AllDay에 split_1st_cooldown_sec 컬럼 추가
--     Opening(opening_scanner_config)은 V126에서 이미 보유.
-- ============================================================
ALTER TABLE morning_rush_config
ADD COLUMN IF NOT EXISTS split_1st_cooldown_sec INT NOT NULL DEFAULT 60;

ALTER TABLE allday_scanner_config
ADD COLUMN IF NOT EXISTS split_1st_cooldown_sec INT NOT NULL DEFAULT 60;
