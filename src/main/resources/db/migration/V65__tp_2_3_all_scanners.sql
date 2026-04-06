-- V65: 모든 스캐너 TP 2.3% 통일 (슬리피지 감안)
-- 모닝러쉬: tp_pct 2.0 → 2.3
UPDATE morning_rush_config SET tp_pct = 2.30 WHERE id = 1;

-- 오프닝: tpActivatePct는 코드에서 설정 (config entity에 없음)
-- → OpeningScannerService.start()에서 breakoutDetector.setTpActivatePct(2.3) 으로 변경 필요
