-- V118: Position 테이블에 peak_price / armed_at 영속화
-- 목적: 서버 재시작 시 실제 peak를 잃지 않도록 DB 저장
-- 현재: 재시작 시 "현재가 기준 재판정" (V115) → 실제 peak보다 낮은 값이 peak로 기록되는 엣지 발생
-- 개선: peak_price / armed_at 영속화로 재시작 전 상태 그대로 복원

ALTER TABLE position ADD COLUMN peak_price DECIMAL(28, 8) NULL;
ALTER TABLE position ADD COLUMN armed_at TIMESTAMP NULL;
