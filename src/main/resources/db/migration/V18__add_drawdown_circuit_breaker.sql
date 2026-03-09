-- V18: 최대 드로다운 서킷브레이커 + 트레일링 스탑
-- 드로다운: 설정 기간 내 실현+미실현 PnL이 한도 이하로 떨어지면 신규 매수 차단
-- 트레일링 스탑: 글로벌 트레일링 스탑 퍼센트 (0 = 비활성, 각 전략의 자체 트레일링이 우선)

ALTER TABLE bot_config ADD COLUMN max_drawdown_pct DECIMAL(10,4) NOT NULL DEFAULT 0;
ALTER TABLE bot_config ADD COLUMN trailing_stop_pct DECIMAL(10,4) NOT NULL DEFAULT 0;
ALTER TABLE bot_config ADD COLUMN daily_loss_limit_pct DECIMAL(10,4) NOT NULL DEFAULT 0;
