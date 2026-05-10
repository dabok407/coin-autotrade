-- V142: 마켓 추적 확장 (현재 통합 88~100개 → 통합 ~180개 목표)
--
-- 배경: 5/9 분석에서 진짜 급등 TOP 20 중 봇 추적은 7개(35%)뿐
--   - ORCA(+16%), CARV(+11%), IOTA(+6.7%) 등 13개 추적 밖
--
-- 변경:
--   OpeningScanner.top_n  50 → 100
--   AllDayScanner.top_n   50 → 100
--   MorningRush.top_n     30 → 80
--
-- 통합 추적 추정: 100+100+80=280 마켓에서 중복 제거 → ~180개
-- 코드 한도: setTopN clamp 100 → 200 (3 entity 변경, V142 코드 변경)
--
-- 성능 추정 (5/10 베이스라인 측정):
--   현재 88~100 markets, CPU 0.4%, MEM 19.7%, Full GC 0회
--   180 markets 예상: CPU 0.7~1.0%, 메모리 +30MB, 여전히 안전
--
-- Author: dabok407 (2026-05-10)

UPDATE opening_scanner_config SET top_n = 100 WHERE id = 1;
UPDATE allday_scanner_config  SET top_n = 100 WHERE id = 1;
UPDATE morning_rush_config    SET top_n = 80  WHERE id = 1;
