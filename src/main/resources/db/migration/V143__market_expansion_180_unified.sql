-- V143: 3 스캐너 top_n 모두 180으로 통일 (V142 보강)
--
-- V142: OP=100, AD=100, MR=80 → 통합 추적 ~180 추정
-- 사용자 요청: 각 스캐너 자체가 180개 추적
-- V143: OP=180, AD=180, MR=180 → 통합 ~200+ (거래대금 작은 코인까지)
--
-- 효과:
--   - 5/9 진짜 급등 TOP 20 중 ORCA, CARV, IOTA, EDGE 등 거래대금 작은 코인까지 추적
--   - 추적률 35% → 90%+ 추정
--
-- 성능 추정 (5/10 18:20 베이스라인 기준):
--   현재: CPU 3.7%, MEM 17.6%, 9.3 msgs/sec, Full GC 0
--   180×3 통합 ~200+ markets: CPU 7-10% 추정, 안전
--
-- 한도:
--   setTopN clamp Math.min(200, topN) 통과 (V142 코드 변경)
--
-- Author: dabok407 (2026-05-10)

UPDATE opening_scanner_config SET top_n = 180 WHERE id = 1;
UPDATE allday_scanner_config  SET top_n = 180 WHERE id = 1;
UPDATE morning_rush_config    SET top_n = 180 WHERE id = 1;
