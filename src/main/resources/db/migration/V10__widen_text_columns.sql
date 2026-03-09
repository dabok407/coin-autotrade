-- V10: 전략 확장에 따른 컬럼 길이 확대
-- strategy_types_csv: 14개 전략 CSV = 295자, 향후 확장 대비 1024
-- pattern_reason / note: 새 전략(ATM/REGIME)의 상세 reason + uuid 포함 시 255자 초과 가능
ALTER TABLE bot_config MODIFY COLUMN strategy_types_csv VARCHAR(1024) NOT NULL DEFAULT '';
ALTER TABLE trade_log  MODIFY COLUMN note VARCHAR(512) NULL;
ALTER TABLE trade_log  MODIFY COLUMN pattern_reason VARCHAR(512) NULL;
