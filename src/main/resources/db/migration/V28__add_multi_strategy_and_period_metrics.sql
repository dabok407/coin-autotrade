-- V28: 다중 전략 조합 + 기간별 성과 메트릭 추가

-- 다중 전략 조합 식별
ALTER TABLE optimization_result ADD COLUMN strategies_csv VARCHAR(500);
ALTER TABLE optimization_result ADD COLUMN strategy_intervals_csv VARCHAR(200);
ALTER TABLE optimization_result ADD COLUMN ema_filter_csv VARCHAR(200);
ALTER TABLE optimization_result ADD COLUMN phase INT DEFAULT 1;

-- 기간별 성과 (최근 3개월)
ALTER TABLE optimization_result ADD COLUMN roi_3m DOUBLE;
ALTER TABLE optimization_result ADD COLUMN win_rate_3m DOUBLE;
ALTER TABLE optimization_result ADD COLUMN total_trades_3m INT;
ALTER TABLE optimization_result ADD COLUMN wins_3m INT;

-- 기간별 성과 (최근 1개월)
ALTER TABLE optimization_result ADD COLUMN roi_1m DOUBLE;
ALTER TABLE optimization_result ADD COLUMN win_rate_1m DOUBLE;
ALTER TABLE optimization_result ADD COLUMN total_trades_1m INT;
ALTER TABLE optimization_result ADD COLUMN wins_1m INT;

-- 인덱스
CREATE INDEX IF NOT EXISTS idx_opt_phase_market ON optimization_result (phase, market, roi DESC);
