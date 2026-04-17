-- 올데이 스캐너 최소 신뢰도 9.4 → 7.0 완화
-- 근거: 4/14~4/17 백테스트 score 7.0+ 7건 중 TP 5건(승률 83%), 평균 +1.40%
UPDATE allday_scanner_config SET min_confidence = 7.0 WHERE id = 1;
