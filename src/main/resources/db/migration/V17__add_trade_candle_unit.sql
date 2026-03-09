-- 거래 로그에 실행 시 사용된 분봉 단위를 저장
-- 차트 팝업에서 정확한 분봉을 표시하기 위해 필요
ALTER TABLE trade_log ADD COLUMN candle_unit_min INT DEFAULT NULL;
