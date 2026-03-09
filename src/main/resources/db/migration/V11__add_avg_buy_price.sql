-- V11: 매도 시 매수 평균가 저장 (매수-매도 연결 조회용)
ALTER TABLE trade_log ADD COLUMN avg_buy_price DECIMAL(28,12) NULL;
