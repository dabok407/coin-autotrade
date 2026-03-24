-- V44: OPEN_FAILED 청산 조건 옵션화 + SL 기본값 2.0% 적용
ALTER TABLE opening_scanner_config ADD COLUMN open_failed_enabled BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE opening_scanner_config SET sl_pct = 2.0 WHERE sl_pct != 2.0;
