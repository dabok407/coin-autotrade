-- V24: Fix strategy groups and market config
-- SOL, ADA만 실제 매매 대상. BTC/ETH/XRP는 테스트 전용 → 비활성화
-- XRP 전략 그룹 제거 (테스트 전용이므로 매매 그룹에서 제외)

-- 1) XRP 그룹 제거
DELETE FROM strategy_group WHERE markets_csv = 'KRW-XRP';

-- 2) SOL, ADA만 활성화, 나머지 비활성화
UPDATE market_config SET enabled = FALSE WHERE market NOT IN ('KRW-SOL', 'KRW-ADA');
UPDATE market_config SET enabled = TRUE  WHERE market IN ('KRW-SOL', 'KRW-ADA');
