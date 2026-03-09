-- V12: 전략 잠금(Strategy Lock) 기능
-- 매수한 전략만 매도할 수 있도록 제한하는 옵션

-- bot_config에 전략 잠금 토글 추가
ALTER TABLE bot_config ADD COLUMN strategy_lock TINYINT(1) NOT NULL DEFAULT 0;

-- position에 진입 전략 기록 (매수한 전략 이름)
ALTER TABLE position ADD COLUMN entry_strategy VARCHAR(100) NULL;
