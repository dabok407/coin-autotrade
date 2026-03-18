-- V32: 오프닝 스캐너 v2 최적화 기본값 업데이트
-- 기존 기본값(v1)을 가진 경우에만 업데이트하여 사용자 커스텀 설정 보호

-- SL: 10%→2% (스캘핑 적합, v1 기본값일 때만)
UPDATE opening_scanner_config SET sl_pct = 2.0 WHERE id = 1 AND sl_pct = 10.0;

-- TP: 1.2→1.5 ATR (v1 기본값일 때만)
UPDATE opening_scanner_config SET tp_atr_mult = 1.5 WHERE id = 1 AND tp_atr_mult = 1.2;

-- Trail: 0.8→0.6 ATR (v1 기본값일 때만)
UPDATE opening_scanner_config SET trail_atr_mult = 0.6 WHERE id = 1 AND trail_atr_mult = 0.8;

-- Volume: 1.5 유지 (변경 없음 — 에이전트 합의: 완화 금지)
-- Body ratio: 0.40→0.45 (강화, v1 기본값일 때만)
UPDATE opening_scanner_config SET min_body_ratio = 0.45 WHERE id = 1 AND min_body_ratio = 0.40;
