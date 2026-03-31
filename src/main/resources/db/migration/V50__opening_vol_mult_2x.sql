-- V50: 오프닝 vol 4.0x → 2.0x
-- 정밀 시뮬레이션 결과 (29코인 x 21일 x 87,000캔들):
-- vol 2.0x: 22건, 승률 68.2%, 총PnL +11.59% (최적)
-- vol 4.0x: 16건, 승률 62.5%, 총PnL +2.28%

UPDATE opening_scanner_config SET volume_mult = 2.0 WHERE id = 1;
