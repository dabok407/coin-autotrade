-- V34: 포지션 동기화 버그로 인한 잘못된 거래 기록 정리
-- 봇이 수동 보유 코인을 자동 등록(BUY_SYNC) 후 매도한 기록 삭제
-- 이후 syncPositionsFromUpbit()에 안전장치 추가로 재발 방지됨

-- POSITION_SYNC로 생성된 BUY_SYNC 기록 삭제
DELETE FROM trade_log WHERE action = 'BUY_SYNC' AND pattern_type = 'POSITION_SYNC';

-- SELL_SYNC 기록도 삭제
DELETE FROM trade_log WHERE action = 'SELL_SYNC' AND pattern_type = 'POSITION_SYNC';

-- 고아 포지션 정리 (qty가 0 이하인 포지션)
DELETE FROM position WHERE qty <= 0;
