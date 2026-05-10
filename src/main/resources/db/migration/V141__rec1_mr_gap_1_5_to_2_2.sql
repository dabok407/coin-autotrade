-- V141: 모닝러쉬 gap 임계값 변경 (백테스트 1+2+4 권고 #1)
--
-- 배경: 5/1~5/10 160 케이스 백테스트 결과
--   gap 1.5-2.0%: 승률 56% ROI +0.32% (최적)
--   gap 2.5-3.0%: 승률 20% ROI -1.26% (손실)
--   gap 4.0-6.0%: 승률  0% ROI -2.50% (확실한 손실)
--
-- 변경: gap_threshold_pct 2.6% → 1.5% (하한)
-- 코드: MorningRushScannerService에 gap 상한 2.2% 추가 (V141 코드 변경)
-- 결과: 진입 조건 1.5% ≤ gap < 2.2%
--
-- 근거: 1+2+4 조합 시뮬 — 28건 진입, 승률 57.1%, ROI +1.04%
-- 영향: 모닝러쉬 + Opening BREAKOUT (range_high 대비 gap)
--
-- Author: dabok407 (2026-05-10)

UPDATE morning_rush_config
   SET gap_threshold_pct = 1.5
 WHERE id = 1;
