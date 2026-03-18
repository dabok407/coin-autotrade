-- V35: 포지션 동기화 버그 잔여 기록 정리
-- V34에서 BUY_SYNC를 삭제했으나, 봇이 외부 코인을 매도한 일반 SELL 기록이 남아 PnL 왜곡
-- 봇이 BUY한 적 없는 마켓의 SELL 기록 = 동기화 버그로 인한 잘못된 매도

-- 봇이 직접 BUY한 적 없는 마켓의 SELL 기록 삭제
DELETE FROM trade_log
WHERE action = 'SELL'
  AND market NOT IN (
    SELECT DISTINCT market FROM trade_log WHERE action = 'BUY'
  );

-- 고아 포지션 재정리
DELETE FROM position WHERE qty <= 0;
