-- V126: Opening 스캐너 Split-Exit 1차 매도 이후 60초 쿨다운
-- 근거: 30일 백테스트(Agent 3) S2(T60+B60) 결과 avgPnL -0.287% → -0.250% (+0.037%p 개선).
-- SPLIT_1ST 체결 후 60초 동안 BEV(SPLIT_2ND_BEV) 및 SPLIT_2ND_TRAIL 매도를 차단.
-- peak 갱신은 유지(엣지 추적). SL_WIDE/SL_TIGHT는 별도 경로로 쿨다운 영향 없음(Agent 3 근거).
--
-- Opening만 적용 (MR/AllDay 제외).

-- 1) opening_scanner_config: 쿨다운 초 단위 값 (DB화, UI에서 조정 가능)
ALTER TABLE opening_scanner_config
ADD COLUMN split_1st_cooldown_sec INT NOT NULL DEFAULT 60;

-- 2) position: SPLIT_1ST 체결 시점 영속화 (재시작 후 쿨다운 상태 복원)
ALTER TABLE position
ADD COLUMN split_1st_executed_at TIMESTAMP NULL;
