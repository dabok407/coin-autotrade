-- V52: 올데이 스캐너 Quick TP 비활성화
-- 올데이는 고확신(9.4+) 추세 진입 → 충분히 보유해야 수익 극대화
-- QTP 0.7%에서 빠른 익절하면 전략 취지와 맞지 않음
-- ATR TP(3.5 ATR) + 트레일링(1.5 ATR) + EMA 청산으로 충분

UPDATE allday_scanner_config SET quick_tp_enabled = false WHERE id = 1;
