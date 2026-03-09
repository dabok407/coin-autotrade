-- V13: 패턴 신뢰도 점수(Confidence Score) 기능
-- 전략 신호의 명확도를 1~10 점수로 평가, 최소 점수 이하 신호 필터링

-- bot_config에 최소 진입 점수 설정 추가 (0 = 필터링 비활성)
ALTER TABLE bot_config ADD COLUMN min_confidence DOUBLE NOT NULL DEFAULT 0;

-- trade_log에 신뢰도 점수 기록
ALTER TABLE trade_log ADD COLUMN confidence DOUBLE NULL DEFAULT NULL;
