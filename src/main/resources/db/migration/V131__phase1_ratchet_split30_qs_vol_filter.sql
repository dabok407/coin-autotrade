-- V131: Phase 1 — Trail Ratchet + Split 30/70 + OP 진입 필터 (qs/vol)
-- 근거: .analysis/FINAL_COMPREHENSIVE_REPORT_2026-05-02.md (7명 에이전트 종합)
--   1) Trail Ratchet — Agent F 권고: 코인 1분봉 pull back 2~5%로 trail 2% 항상 초과
--      peak<2: 0.7% / <3: 1.0% / <5: 2.5% / >=5: 4.0% (Agent G E-2 시뮬: avgWin +88%)
--   2) Split 30/70 — Agent D 종합 1위: V130 baseline 대비 +2,284원 (큰추세 보존)
--   3) OP 진입 필터 — Agent A 권고: DRIFT류(-14,714) 차단 (qs ≥ 3.5, vol ≥ 5.0)
--
-- 단계: Phase 1 (이번 주). PF 0.39 → 0.50~0.55 목표.
-- Phase 2(동적 ATR SL), Phase 3(Split 3단계+큰추세분리) 별도 마이그레이션.

-- ============================================================
-- (1) Trail Ratchet — V130 ladder default 값 변경
--     기존: 0.5/1.0/1.5/2.0  → 신규: 0.7/1.0/2.5/4.0
--     trail_after: 1.0/1.2/1.5/2.0 → 1.0/1.5/2.5/5.0
-- ============================================================

-- MR: split_1st ladder
UPDATE morning_rush_config SET split_1st_drop_under_2 = 0.70 WHERE id = 1;
UPDATE morning_rush_config SET split_1st_drop_under_3 = 1.00 WHERE id = 1;
UPDATE morning_rush_config SET split_1st_drop_under_5 = 2.50 WHERE id = 1;
UPDATE morning_rush_config SET split_1st_drop_above_5 = 4.00 WHERE id = 1;

-- MR: trail_after ladder
UPDATE morning_rush_config SET trail_after_drop_under_2 = 1.00 WHERE id = 1;
UPDATE morning_rush_config SET trail_after_drop_under_3 = 1.50 WHERE id = 1;
UPDATE morning_rush_config SET trail_after_drop_under_5 = 2.50 WHERE id = 1;
UPDATE morning_rush_config SET trail_after_drop_above_5 = 5.00 WHERE id = 1;

-- OP: split_1st ladder
UPDATE opening_scanner_config SET split_1st_drop_under_2 = 0.70 WHERE id = 1;
UPDATE opening_scanner_config SET split_1st_drop_under_3 = 1.00 WHERE id = 1;
UPDATE opening_scanner_config SET split_1st_drop_under_5 = 2.50 WHERE id = 1;
UPDATE opening_scanner_config SET split_1st_drop_above_5 = 4.00 WHERE id = 1;

-- OP: trail_after ladder
UPDATE opening_scanner_config SET trail_after_drop_under_2 = 1.00 WHERE id = 1;
UPDATE opening_scanner_config SET trail_after_drop_under_3 = 1.50 WHERE id = 1;
UPDATE opening_scanner_config SET trail_after_drop_under_5 = 2.50 WHERE id = 1;
UPDATE opening_scanner_config SET trail_after_drop_above_5 = 5.00 WHERE id = 1;

-- AD: split_1st ladder
UPDATE allday_scanner_config SET split_1st_drop_under_2 = 0.70 WHERE id = 1;
UPDATE allday_scanner_config SET split_1st_drop_under_3 = 1.00 WHERE id = 1;
UPDATE allday_scanner_config SET split_1st_drop_under_5 = 2.50 WHERE id = 1;
UPDATE allday_scanner_config SET split_1st_drop_above_5 = 4.00 WHERE id = 1;

-- AD: trail_after ladder
UPDATE allday_scanner_config SET trail_after_drop_under_2 = 1.00 WHERE id = 1;
UPDATE allday_scanner_config SET trail_after_drop_under_3 = 1.50 WHERE id = 1;
UPDATE allday_scanner_config SET trail_after_drop_under_5 = 2.50 WHERE id = 1;
UPDATE allday_scanner_config SET trail_after_drop_above_5 = 5.00 WHERE id = 1;

-- ============================================================
-- (2) Split 비율 0.50 → 0.30 (1차 30% 매도, 2차 70% 보유)
--     Agent D 종합 권고. 큰 추세에서 70%를 더 오래 보유.
-- ============================================================
UPDATE morning_rush_config    SET split_ratio = 0.30 WHERE id = 1;
UPDATE opening_scanner_config SET split_ratio = 0.30 WHERE id = 1;
UPDATE allday_scanner_config  SET split_ratio = 0.30 WHERE id = 1;

-- ============================================================
-- (3) OP 진입 필터 — DRIFT류 차단 (qs/vol 신규 컬럼 + 기본값)
--     Agent A 권고: DRIFT 진입 직전봉 qs 점수 낮음, vol 낮음
--     OP 스캐너만 적용 (DRIFT/RAY/MANTRA 모두 OP에서 발생)
-- ============================================================
-- 컬럼 추가 시 default는 V130 호환(2.0/0)으로 두고, 운영값은 아래 UPDATE로 설정
-- (기존 테스트가 V130 default 동작을 가정하므로 NEW 행에 V130 호환 default 적용)
ALTER TABLE opening_scanner_config ADD COLUMN IF NOT EXISTS min_qs_score DECIMAL(4,2) NOT NULL DEFAULT 2.0;
ALTER TABLE opening_scanner_config ADD COLUMN IF NOT EXISTS min_vol_mult DECIMAL(4,2) NOT NULL DEFAULT 0.0;
-- 운영값 적용: V131 진입 필터 강화
UPDATE opening_scanner_config SET min_qs_score = 3.5 WHERE id = 1;
UPDATE opening_scanner_config SET min_vol_mult = 5.0 WHERE id = 1;

-- ============================================================
-- (4) L1 +2.0% 강제 익절 캡 — 사용자 첫 의견
--     Agent E 시뮬 22건 기준 +4,371~+16,640 KRW 개선 확정
--     L1 trail armed 후 ROI 도달 시 강제 split1 매도
--     0.0 = 비활성. 기본 2.0%.
-- ============================================================
-- 컬럼 default는 V130 호환(0=비활성)으로, 운영값은 UPDATE로 2.0 설정
ALTER TABLE morning_rush_config    ADD COLUMN IF NOT EXISTS l1_cap_pct DECIMAL(5,2) NOT NULL DEFAULT 0.0;
ALTER TABLE opening_scanner_config ADD COLUMN IF NOT EXISTS l1_cap_pct DECIMAL(5,2) NOT NULL DEFAULT 0.0;
ALTER TABLE allday_scanner_config  ADD COLUMN IF NOT EXISTS l1_cap_pct DECIMAL(5,2) NOT NULL DEFAULT 0.0;
-- 운영값 적용: V131 L1 강제 익절 캡 +2.0%
UPDATE morning_rush_config    SET l1_cap_pct = 2.0 WHERE id = 1;
UPDATE opening_scanner_config SET l1_cap_pct = 2.0 WHERE id = 1;
UPDATE allday_scanner_config  SET l1_cap_pct = 2.0 WHERE id = 1;
