-- V134: Phase 2b — 동적 ATR SL Service 적용 + 운영값 활성화
-- V132에서 Entity/DB 컬럼만 준비 (sl_atr_enabled=FALSE).
-- V134에서 Service 코드 적용 + 운영값 활성화.
--
-- Service 동작:
--   매수 시점 또는 mainLoop 동기화에서 ATR(14, 1분봉) 측정 → dynamic_sl_pct = clamp(atr_pct * mult, min, max)
--   checkRealtimeTpSl SL_TIGHT 분기에서 dynamicSlMap.get(market) 우선 사용 (없으면 cachedSlPct fallback)
--
-- 효과: avgLoss 6,180 → 4,000원 (Agent G 추정), PF 0.39 → 1.1 도달 핵심
--
-- ATR 측정 조건:
--   - 1분봉 14개 이상 확보된 후 한 번 계산
--   - 진입가 P0 기준 atr_pct = atr / P0 * 100
--   - sl_atr_min_pct ~ sl_atr_max_pct 범위 clamp (default 1.5~3.5%)

UPDATE morning_rush_config    SET sl_atr_enabled = TRUE WHERE id = 1;
UPDATE opening_scanner_config SET sl_atr_enabled = TRUE WHERE id = 1;
UPDATE allday_scanner_config  SET sl_atr_enabled = TRUE WHERE id = 1;
