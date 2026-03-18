-- V36: Opening Scanner capital을 Global Capital(bot_config.capital_krw)로 통합
-- 기본 전략과 오프닝 전략이 하나의 Capital 풀을 공유하도록 변경.
-- 우선순위 없이 먼저 매수 시그널이 발생한 쪽이 Capital을 차지함.
-- capital_krw 컬럼은 유지하되 더 이상 사용하지 않음 (backward compatibility).
UPDATE opening_scanner_config SET capital_krw = -1 WHERE id = 1;
