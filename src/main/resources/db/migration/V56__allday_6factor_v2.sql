-- V56: 올데이 전략 v2 — 6-Factor 스코어링 (10일 50코인 데이터 검증)
--
-- 변경: 8-Factor → 6-Factor
--   삭제: MACD, ADX, ATR, EMA50 정렬 (예측력 없음)
--   추가: 전날종가 대비 상승률
--   교체: EMA50 → EMA21 기울기
--   RSI: 65-75 구간 0.5점 (기존 0점)
--
-- minConfidence: 9.4 → 8.1 (10점 환산, 내부 6.5/8.0 = 81%)
-- grace_period_candles: 6 → 12 (10일 분석: PnL +61% → +142%)

UPDATE allday_scanner_config SET
    min_confidence = 8.1,
    grace_period_candles = 12
WHERE id = 1;
