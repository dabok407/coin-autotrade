-- pnl_krw, roi_percent 컬럼에 NOT NULL DEFAULT 0 제약 추가
-- 기존 NULL 값을 0으로 업데이트 후 제약 적용
UPDATE trade_log SET pnl_krw = 0 WHERE pnl_krw IS NULL;
UPDATE trade_log SET roi_percent = 0 WHERE roi_percent IS NULL;

ALTER TABLE trade_log ALTER COLUMN pnl_krw SET DEFAULT 0;
ALTER TABLE trade_log ALTER COLUMN pnl_krw SET NOT NULL;

ALTER TABLE trade_log ALTER COLUMN roi_percent SET DEFAULT 0;
ALTER TABLE trade_log ALTER COLUMN roi_percent SET NOT NULL;
