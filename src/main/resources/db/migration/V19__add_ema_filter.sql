-- 전략별 EMA 트렌드 필터 오버라이드 (CSV: "TYPE:PERIOD,TYPE:PERIOD,...")
-- 비어있으면 모든 전략이 기본값(50) 사용 (기존 동작 유지)
-- PERIOD=0이면 해당 전략의 EMA 필터 비활성화
ALTER TABLE bot_config ADD COLUMN ema_filter_csv VARCHAR(2048) NOT NULL DEFAULT '';
