-- V128: trade_log에 peak/armed/entry_signal 컬럼 추가 (순수 로깅, 매매 로직 영향 없음)
--
-- 목적: 거래내역 화면에서 매수 유형, 최고 peak 가격/ROI%, SPLIT_1ST armed 성공 여부 확인
-- 영향: 신규 컬럼 NULL 허용, 기존 row는 NULL로 유지 (과거 거래 분석은 로그 파일 grep 필요)
--
-- 매수 row에는 entry_signal (bo/vol/rsi/qs 등) 기록
-- 매도 row에는 peak_price + peak_roi_pct + armed_flag ('Y'/'N') 기록

ALTER TABLE trade_log ADD COLUMN IF NOT EXISTS peak_price    DECIMAL(28,12) DEFAULT NULL;
ALTER TABLE trade_log ADD COLUMN IF NOT EXISTS peak_roi_pct  DECIMAL(12,4)  DEFAULT NULL;
ALTER TABLE trade_log ADD COLUMN IF NOT EXISTS armed_flag    VARCHAR(8)     DEFAULT NULL;
ALTER TABLE trade_log ADD COLUMN IF NOT EXISTS entry_signal  VARCHAR(256)   DEFAULT NULL;
