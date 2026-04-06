-- V54: 오프닝 스캐너 거래량 배수 2.0x → 5.0x
-- 이번주(3/25~4/1) 39건 분석: vol<5x 28건이 -57,011원 손실
-- vol>=5x 필터 적용 시 11건, 승률 63.6%, PnL +24,369원

UPDATE opening_scanner_config SET volume_mult = 5.0 WHERE id = 1;
