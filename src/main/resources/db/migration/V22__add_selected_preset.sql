-- V22: 전략 그룹에 선택된 프리셋 키 저장 (예: BULL_AGG, SIDE_STB 등)
ALTER TABLE strategy_group ADD COLUMN selected_preset VARCHAR(20) DEFAULT NULL;
