-- V64: E2E PAPER 테스트 포지션 정리
-- DRIFT, CFG, KITE: PAPER 테스트에서 생성된 포지션 삭제
DELETE FROM position WHERE market = 'KRW-DRIFT';
DELETE FROM position WHERE market = 'KRW-CFG';
DELETE FROM position WHERE market = 'KRW-KITE';

-- PAPER 테스트 거래 로그도 정리 (PAPER 모드 거래)
DELETE FROM trade_log WHERE market IN ('KRW-DRIFT', 'KRW-CFG', 'KRW-KITE', 'KRW-ZK')
  AND mode = 'PAPER' AND ts_epoch_ms > 1775282400000;
