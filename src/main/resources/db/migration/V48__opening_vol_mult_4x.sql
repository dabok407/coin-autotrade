-- V48: 오프닝 스캐너 거래량 배수 1.5x → 4.0x
-- 근거: 1주일 실거래 31건 분석 결과
--   vol < 5.0x → 6건 전부 손실 (평균 -2.44%)
--   vol ≥ 5.0x → 4건 전부 수익 (평균 +5.67%)
--   4.0x는 KITE(4.3x) 1건만 통과, 나머지 손실 5건 차단

UPDATE opening_scanner_config SET volume_mult = 4.0 WHERE id = 1;
