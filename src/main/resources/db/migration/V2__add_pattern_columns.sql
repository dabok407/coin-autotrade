-- 매매유형(전략/패턴) 컬럼 추가
ALTER TABLE trade_log ADD COLUMN pattern_type VARCHAR(64) NULL;
ALTER TABLE trade_log ADD COLUMN pattern_reason VARCHAR(255) NULL;
