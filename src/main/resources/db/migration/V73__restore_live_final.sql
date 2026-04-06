-- V73: E2E 테스트 완료 → LIVE 운영 원복

-- 모닝러쉬: LIVE, 운영 설정
UPDATE morning_rush_config SET
  mode = 'LIVE',
  tp_pct = 2.30,
  sl_pct = 3.0,
  gap_threshold_pct = 2.0,
  volume_mult = 5.0,
  confirm_count = 3,
  session_end_hour = 10,
  session_end_min = 0
WHERE id = 1;

-- 올데이: LIVE, 운영 설정
UPDATE allday_scanner_config SET
  mode = 'LIVE',
  entry_start_hour = 10,
  entry_start_min = 35,
  entry_end_hour = 22,
  entry_end_min = 0,
  quick_tp_pct = 2.30
WHERE id = 1;

-- 오프닝: LIVE, 운영 설정
UPDATE opening_scanner_config SET
  mode = 'LIVE',
  entry_start_hour = 9,
  entry_start_min = 0,
  entry_end_hour = 10,
  entry_end_min = 25,
  session_end_hour = 12,
  session_end_min = 0
WHERE id = 1;

-- 테스트 PAPER 포지션 정리
DELETE FROM position WHERE market IN (
  'KRW-KERNEL', 'KRW-1INCH', 'KRW-THETA',
  'KRW-BERA', 'KRW-KAT', 'KRW-CVC',
  'KRW-ZETA', 'KRW-CFG', 'KRW-XPL',
  'KRW-T', 'KRW-DRIFT', 'KRW-ONG',
  'KRW-VET', 'KRW-ZK'
);

-- 테스트 PAPER 거래 로그 정리
DELETE FROM trade_log WHERE mode = 'PAPER' AND ts_epoch_ms > 1775282400000;
