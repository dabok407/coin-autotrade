-- Time Stop: 매수 전용 전략의 포지션이 설정 시간 초과 + 손실 상태이면 청산 (분 단위, 0 = 비활성)
ALTER TABLE bot_config ADD COLUMN time_stop_minutes INT NOT NULL DEFAULT 0;
