-- V127: Opening 스캐너 C4 vol3 비율 임계값 DB화 + 2.5x 상향
-- 근거: 30일 백테스트(A1 재현 + A2 경로검증 + X 60일 확장) 방향성 일관.
--   BASE(1.5x) → C4_vol3_2.5x: WR +6~10%p, avgROI +0.28~0.35%p (기간 평균).
-- 기존 하드코딩 OpeningScannerService.java:1840의 "1.5" 상수를 DB값으로 이동.
-- UI에서 1.0~5.0 범위로 조정 가능(롤백 즉시 반영).
-- Opening 전용 (MR/AllDay 영향 없음).
--
-- 롤백: UI에서 vol3_ratio_threshold = 1.5 입력 후 저장 → 5초 내 scanner refresh 반영.

ALTER TABLE opening_scanner_config
ADD COLUMN vol3_ratio_threshold DECIMAL(5,2) NOT NULL DEFAULT 2.50;
