-- V124: Chase Guard 롤백 — V123에서 추가한 chase_guard_pct 컬럼 제거
-- 사유: 4주 재백테스트 결과 수익 거래 다수 차단 확인 (Opening X=1.5%에서 10건 차단,
-- X=2%에서 -8,803원 손실), 급등 초기 2~3% 구간 승률 75%(Opening)/83%(MR>3%)로
-- 오히려 수익 패턴이었음. 코드 로직과 스키마 모두 제거.

ALTER TABLE opening_scanner_config DROP COLUMN IF EXISTS chase_guard_pct;
ALTER TABLE allday_scanner_config DROP COLUMN IF EXISTS chase_guard_pct;
