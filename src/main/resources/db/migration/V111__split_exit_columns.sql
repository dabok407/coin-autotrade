-- Split-Exit 분할 익절 DB 스키마 (2026-04-14)
-- 토글 OFF면 기존 전량 매도, ON이면 분할 매도

-- 1. position 테이블: 분할 상태 추적
ALTER TABLE position ADD COLUMN IF NOT EXISTS split_phase INT DEFAULT 0;
-- 0=초기(미분할), 1=1차 매도 완료(40% 잔량), -1=분할 불가
ALTER TABLE position ADD COLUMN IF NOT EXISTS split_original_qty DECIMAL(28,18);
-- 1차 매도 전 원래 수량 (자본 계산용)

-- 2. 모닝러쉬 config: 분할 매도 설정
ALTER TABLE morning_rush_config ADD COLUMN IF NOT EXISTS split_exit_enabled BOOLEAN DEFAULT FALSE;
ALTER TABLE morning_rush_config ADD COLUMN IF NOT EXISTS split_tp_pct DECIMAL(5,2) DEFAULT 1.5;
ALTER TABLE morning_rush_config ADD COLUMN IF NOT EXISTS split_ratio DECIMAL(4,2) DEFAULT 0.60;
ALTER TABLE morning_rush_config ADD COLUMN IF NOT EXISTS trail_drop_after_split DECIMAL(5,2) DEFAULT 1.0;

-- 3. 오프닝 config: 분할 매도 설정
ALTER TABLE opening_scanner_config ADD COLUMN IF NOT EXISTS split_exit_enabled BOOLEAN DEFAULT FALSE;
ALTER TABLE opening_scanner_config ADD COLUMN IF NOT EXISTS split_tp_pct DECIMAL(5,2) DEFAULT 1.5;
ALTER TABLE opening_scanner_config ADD COLUMN IF NOT EXISTS split_ratio DECIMAL(4,2) DEFAULT 0.60;
ALTER TABLE opening_scanner_config ADD COLUMN IF NOT EXISTS trail_drop_after_split DECIMAL(5,2) DEFAULT 1.0;

-- 4. 올데이 config: 분할 매도 설정
ALTER TABLE allday_scanner_config ADD COLUMN IF NOT EXISTS split_exit_enabled BOOLEAN DEFAULT FALSE;
ALTER TABLE allday_scanner_config ADD COLUMN IF NOT EXISTS split_tp_pct DECIMAL(5,2) DEFAULT 1.5;
ALTER TABLE allday_scanner_config ADD COLUMN IF NOT EXISTS split_ratio DECIMAL(4,2) DEFAULT 0.60;
ALTER TABLE allday_scanner_config ADD COLUMN IF NOT EXISTS trail_drop_after_split DECIMAL(5,2) DEFAULT 1.0;
