package com.example.upbit.bot;

/**
 * ============================================================
 * E2E 전체 싸이클 테스트 시나리오 가이드
 * ============================================================
 *
 * 목적: 코인봇 3개 스캐너(모닝러쉬, 오프닝, 종일)의
 *       BUY → TP 매도 → SL 매도 전체 싸이클을 실서버에서 검증.
 *
 * 사용 시점:
 *   - 모든 기능 수정/반영 후 LIVE 배포 전
 *   - 사용자 요청 시 전체 재검증
 *
 * ============================================================
 * [테스트 준비]
 * ============================================================
 *
 * 1. 로컬 빌드:
 *    export JAVA_HOME="/tmp/corretto17_extract/jdk17.0.18_9"
 *    export PATH="$JAVA_HOME/bin:$PATH"
 *    mvn clean package -DskipTests
 *
 * 2. 배포:
 *    PEM=D:/aws/mk-key.pem HOST=ec2-user@13.124.248.221 DIR=/home/ec2-user/coin-autotrade
 *    ssh -i $PEM $HOST "bash $DIR/script/shutdown.sh"
 *    scp -i $PEM target/upbit-autotrade-1.0.0.jar $HOST:$DIR/app.jar
 *    ssh -i $PEM $HOST "bash $DIR/script/startup.sh"
 *
 * 3. 로그 확인:
 *    ssh -i $PEM $HOST "tail -f $DIR/logs/autotrade.log"
 *
 * ============================================================
 * [시나리오 1] 모닝러쉬 TP 테스트
 * ============================================================
 *
 * 코드 변경:
 *   MorningRushScannerService.java:
 *     - HourlyTradeThrottle(999)  // 원본: 2
 *     - isRangePhase: (nowMinOfDay >= H*60+M) && (nowMinOfDay < H*60+M+5)
 *     - isEntryPhase: (nowMinOfDay >= H*60+M+5) && (nowMinOfDay < H*60+M+10)
 *     - isHoldPhase: (nowMinOfDay >= H*60+M+10) && (nowMinOfDay < sessionEndMin)
 *       → H,M은 현재시각 기준 2분 후로 설정
 *
 * DB 변경 (Flyway migration):
 *   UPDATE morning_rush_config SET
 *     mode = 'PAPER',
 *     tp_pct = 0.1,        -- TP 0.1% (즉시 발동)
 *     sl_pct = 99.0,       -- SL 비활성화
 *     session_end_hour = ??, session_end_min = ??,  -- 현재+30분
 *     gap_threshold_pct = 0.1,   -- 매수 기준 완화
 *     volume_mult = 1.0,
 *     confirm_count = 1,
 *     max_positions = 10
 *   WHERE id = 1;
 *
 * 예상 결과:
 *   [MorningRush] BUY KRW-XXX mode=PAPER price=...
 *   [MorningRush] realtime TP detected | KRW-XXX | TP pnl=0.1x% ...
 *   [MorningRush] TP KRW-XXX price=... pnl=... roi=...
 *   → WebSocket 실시간 TP, 2~3초 이내 발동
 *
 * 확인 포인트:
 *   - BUY 후 WebSocket TP 발동 시각 차이 (2~5초 이내)
 *   - morning-rush-tp-sell 스레드에서 매도 실행
 *   - trade_log 기록 확인
 *
 * ============================================================
 * [시나리오 2] 모닝러쉬 SL 테스트
 * ============================================================
 *
 * 시나리오 1과 동일하되:
 *   tp_pct = 99.0,      -- TP 비활성화
 *   sl_pct = 0.1,       -- SL 0.1% (즉시 발동)
 *
 * 예상 결과:
 *   [MorningRush] BUY KRW-XXX ...
 *   [MorningRush] realtime SL detected | KRW-XXX | SL pnl=-0.xx% ...
 *   [MorningRush] SL KRW-XXX price=... pnl=... roi=...
 *   → WebSocket 실시간 SL, 2~3초 이내 발동
 *
 * ============================================================
 * [시나리오 3] 오프닝 스캐너 TP 테스트
 * ============================================================
 *
 * 코드 변경:
 *   ScalpOpeningBreakStrategy.java:
 *     - RANGE_NARROW: 0.3% → 0.01%
 *     - MIN_BREAKOUT_PCT: 1.0% → 0.01%
 *     - RSI_OVERBOUGHT: 83.0 → 99.0
 *     - RANGE_CANDLES: 4 → 1
 *     - breakout 체크(close <= rangeHigh) 비활성화
 *     - EMA20 필터 비활성화
 *
 * DB 변경:
 *   UPDATE opening_scanner_config SET
 *     mode = 'PAPER',
 *     range_start_hour = ??, range_start_min = ??,  -- 현재-10분
 *     range_end_hour = ??, range_end_min = ??,      -- 현재+2분
 *     entry_start_hour = ??, entry_start_min = ??,  -- 현재+3분
 *     entry_end_hour = ??, entry_end_min = ??,      -- 현재+15분
 *     session_end_hour = ??, session_end_min = ??,  -- 현재+30분 (자정 넘기지 않기!)
 *     tp_atr_mult = 0.01,    -- ATR × 0.01 (매수 직후 TP 도달)
 *     sl_pct = 99.0,         -- SL 비활성화
 *     trail_atr_mult = 0.01,
 *     volume_mult = 0.1,     -- 거래량 필터 완화
 *     min_body_ratio = 0.01  -- 양봉 body 필터 완화
 *   WHERE id = 1;
 *
 * 주의사항:
 *   - 5분봉 기반: 캔들 시작시각이 entry 윈도우 내여야 함
 *     예) entry_start=23:10이면 23:10 캔들은 23:15 tick에서 처리
 *   - session_end가 자정(0시) 넘으면 스캐너 비활성화됨!
 *   - 야간 하락장에서는 breakout 체크 비활성화 필수
 *
 * 예상 결과:
 *   [OpeningScanner] BUY KRW-XXX mode=PAPER price=... reason=OPEN_BREAK
 *   [OpeningScanner] SELL KRW-XXX price=... reason=OPEN_TP avg=... tp=...
 *   → 같은 tick 내에서 즉시 OPEN_TP 발동
 *
 * ============================================================
 * [시나리오 4] 오프닝 스캐너 SL 테스트
 * ============================================================
 *
 * 시나리오 3과 동일하되:
 *   tp_atr_mult = 99.0,     -- TP 비활성화
 *   sl_pct = 0.1,           -- SL 0.1% (즉시 발동)
 *   trail_atr_mult = 99.0,  -- 트레일링 비활성화
 *
 * 예상 결과:
 *   [OpeningScanner] BUY KRW-XXX ...
 *   [OpeningScanner] SELL KRW-XXX ... reason=OPEN_HARD_SL ...
 *   → 다음 tick에서 SL 발동 (5분봉 기반 SL)
 *
 * ============================================================
 * [시나리오 5] 종일 스캐너 TP 테스트
 * ============================================================
 *
 * DB 변경:
 *   UPDATE allday_scanner_config SET
 *     mode = 'PAPER',
 *     entry_start_hour = ??, entry_start_min = ??,
 *     entry_end_hour = ??, entry_end_min = ??,
 *     quick_tp_pct = 0.1,    -- TP 0.1% (즉시 발동)
 *     sl_pct = 99.0,         -- SL 비활성화
 *     min_confidence = 5.0   -- 매수 기준 완화
 *   WHERE id = 1;
 *
 * 예상 결과:
 *   [AllDayScanner] BUY KRW-XXX mode=PAPER ... reason=HC_BREAK
 *   [AllDayScanner] WS_TP triggered: KRW-XXX price=... pnl=+0.xx%
 *   [AllDayScanner] WS_TP SELL KRW-XXX price=... pnl=... roi=...
 *   → WebSocket 실시간 WS_TP, 2~3초 이내 발동
 *
 * ============================================================
 * [시나리오 6] 종일 스캐너 SL 테스트
 * ============================================================
 *
 * DB 변경:
 *   quick_tp_pct = 99.0,   -- TP 비활성화
 *   sl_pct = 0.1,          -- SL 0.1%
 *
 * 예상 결과:
 *   [AllDayScanner] BUY KRW-XXX ...
 *   [AllDayScanner] SELL KRW-XXX ... reason=HC_SL pnl=-0.xx%
 *   → 5분봉 tick에서 HC_SL 발동
 *
 * ============================================================
 * [원복 체크리스트] - 테스트 완료 후 반드시 수행
 * ============================================================
 *
 * 코드 원복:
 *   MorningRushScannerService.java:
 *     - HourlyTradeThrottle(2)
 *     - isRangePhase: 8*60+50 ~ 9*60
 *     - isEntryPhase: 9*60 ~ 9*60+5
 *     - isHoldPhase: 9*60+5 ~ sessionEndMin
 *
 *   ScalpOpeningBreakStrategy.java:
 *     - RANGE_NARROW: 0.3%
 *     - MIN_BREAKOUT_PCT: 1.0%
 *     - RSI_OVERBOUGHT: 83.0
 *     - RANGE_CANDLES: 4
 *     - breakout 체크 복원: if (close <= rangeHigh) return NO_BREAKOUT
 *     - EMA20 필터 복원: if (close < ema) return BELOW_EMA20
 *
 * DB 원복 (Flyway migration):
 *   morning_rush_config:
 *     mode=LIVE, tp=2.3, sl=3.0, session 10:00,
 *     gap=2.0, vol=5.0, confirm=3, max_positions=2
 *
 *   opening_scanner_config:
 *     mode=LIVE, range 08:00-08:59, entry 09:00-10:30,
 *     session 12:00, tp_atr=1.5, sl=3.0, trail=0.6,
 *     vol=5.0, body=0.45
 *
 *   allday_scanner_config:
 *     mode=LIVE, entry 10:30-22:00, session 09:00(다음날),
 *     quickTP=2.3, sl=3.0, minConf=7.5, maxPos=5
 *
 *   bot_config:
 *     capital_krw=600000
 *
 *   position:
 *     DELETE WHERE entry_strategy IN
 *       ('SCALP_OPENING_BREAK','HIGH_CONFIDENCE_BREAKOUT','MORNING_RUSH')
 *
 * ============================================================
 * [2026-04-06 테스트 실행 결과]
 * ============================================================
 *
 * 시나리오 1 (모닝러쉬 TP) ✅ 통과
 *   22:42:28 BUY KRW-MASK 671원 → 22:42:30 TP SELL 671.3원 (2초)
 *   22:55:03 BUY KRW-FLUID 2462원 → 22:55:06 TP SELL 2462.5원 (3초)
 *
 * 시나리오 2 (모닝러쉬 SL) ✅ 통과
 *   23:02:03 BUY KRW-SUPER 205원 → 23:02:05 SL SELL 203.8원 (2초, pnl=-0.49%)
 *
 * 시나리오 3 (오프닝 TP) ✅ 통과
 *   23:45:08 BUY KRW-TREE 95.70원 → 23:45:08 OPEN_TP SELL 95.50원 (같은 tick)
 *
 * 시나리오 4 (오프닝 SL) ❌ 미완
 *   사유: 야간 하락장 + 자정 넘김 한계로 매수 불가
 *   대안: 아침 09:00에 sl=0.1% PAPER 테스트 또는 단위테스트
 *
 * 시나리오 5 (종일 TP) ✅ 통과 (이전 세션)
 *   21:40:08 BUY KRW-SUPER 207원 → 21:40:10 WS_TP SELL 207.8원 (2초, +0.28%)
 *
 * 시나리오 6 (종일 SL) ✅ 통과 (이전 세션)
 *   22:00:16 BUY KRW-TREE 97.2원 → 22:05:14 HC_SL SELL 92.8원 (-4.52%)
 */
public class E2ETestScenarios {
    // 이 클래스는 E2E 테스트 절차 문서입니다.
    // 실제 테스트는 서버 배포 후 로그로 검증합니다.
    // 단위테스트는 MorningRushTpSlTest, AllDayWsTpTest 등 개별 클래스 참조.
}
