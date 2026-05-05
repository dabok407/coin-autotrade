-- V138: 권고 2 — split_1st_drop_above_5 5.0% → 3.0% (Agent H 권고)
--
-- 배경: 5/4 KRW-AXL 거래에서 peak +7.14%(120원)까지 갔는데 합산 +1.52%만 수익.
--   원인: V133에서 split_1st_drop_above_5와 trail_after_drop_above_5가 둘 다 5.0%로 동시 발동
--         → 1차/2차 매도가 거의 같은 가격에 발생 → 1차 +0.89%, 2차 +1.79%
--
-- Agent H 정밀 백테 결과:
--   - split_1st만 3.0%로 좁히면 AXL ROI +1.46% → +3.60% (+2.14%p 개선)
--   - trail_after는 5.0% 유지 → PRL +14.59% / ENSO +25.7% 같은 큰 추세 보존
--   - 두 마리 토끼: 중간 추세 1차 매도 빨리 챙기기 + 큰 추세 2차에서 끝까지
--
-- 운영 검증 (5/3-5/5 PF 1.35 흑자 작동 중):
--   AXL 같은 케이스 +2~5K/case 추가 개선 추정

-- 운영값 UPDATE (3 스캐너)
UPDATE morning_rush_config    SET split_1st_drop_above_5 = 3.0 WHERE id = 1;
UPDATE opening_scanner_config SET split_1st_drop_above_5 = 3.0 WHERE id = 1;
UPDATE allday_scanner_config  SET split_1st_drop_above_5 = 3.0 WHERE id = 1;
