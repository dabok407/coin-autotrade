package com.example.upbit.strategy;

import com.example.upbit.db.PositionEntity;
import com.example.upbit.market.UpbitCandle;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ScalpOpeningBreakStrategy SESSION_END 오버나잇 시나리오 테스트.
 *
 * 주요 검증:
 *  - sessionEnd 08:50 (오버나잇) 설정 시 09:05~10:30 진입 시간대에 청산 안 됨
 *  - 익일 새벽 08:50~10:00 사이에만 청산 발동
 *  - 정상 sessionEnd 12:00 (당일) 시 12:00 이후 청산
 *
 * 시간 시뮬레이션: 캔들의 candle_date_time_utc를 KST 기준 다양한 시각으로 조작.
 */
public class ScalpOpeningSessionEndScenarioTest {

    private ScalpOpeningBreakStrategy strategy;

    @BeforeEach
    public void setUp() {
        strategy = new ScalpOpeningBreakStrategy()
                .withTiming(8, 0, 8, 59, 9, 0, 10, 30, 8, 50) // sessionEnd 08:50 = 오버나잇
                .withRisk(1.5, 5.0, 0.7) // 넓은 SL로 SL 미발동
                .withFilters(1.5, 0.45);
    }

    /**
     * 시간만 다른 가짜 캔들 리스트 생성.
     * range 캔들(08:00~08:59)은 매수가보다 낮게 → calcRangeHigh가 매수가 미만
     * 진입 후 캔들은 매수가 위 → close > rangeHigh로 OPEN_RANGE_SL 미발동
     *
     * @param hourKst KST 기준 시간
     * @param minKst KST 기준 분
     * @param dayOffset 일 오프셋 (0=당일, 1=익일)
     */
    private List<UpbitCandle> buildCandles(int hourKst, int minKst, int dayOffset, double avgPrice) {
        List<UpbitCandle> candles = new ArrayList<>();

        ZonedDateTime baseKst = ZonedDateTime.of(2026, 4, 7 + dayOffset, hourKst, minKst, 0, 0,
                java.time.ZoneId.of("Asia/Seoul"));

        // range 캔들 (08:00~08:59)은 매수가의 95~98% (rangeHigh ~98%)
        // 진입 후 캔들은 매수가 100% (rangeHigh 위 → range SL 미발동)
        double rangeHighTarget = avgPrice * 0.98;
        double activePrice = avgPrice * 1.001;

        for (int i = 0; i < 60; i++) {
            UpbitCandle c = new UpbitCandle();
            c.market = "KRW-TEST";
            ZonedDateTime candleKst = baseKst.minusMinutes((59 - i) * 5L);
            ZonedDateTime candleUtc = candleKst.withZoneSameInstant(ZoneOffset.UTC);
            c.candle_date_time_utc = candleUtc.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));

            int hour = candleKst.getHour();
            boolean isLast = (i == 59);
            // calcRangeHigh는 같은 날짜 + 08:00~08:59 + 마지막 캔들 제외
            // 마지막 캔들이 같은 날 08:30 같은 경우는 isRangeCandle 기준이 매우 작아짐
            // 단순화: 08시(8시 ~ 8:59)이고 마지막이 아닌 경우 → range candle
            boolean isRangeCandle = (hour == 8) && !isLast;

            if (isRangeCandle) {
                // range 캔들: 매수가의 95~97%
                c.opening_price = avgPrice * 0.97;
                c.trade_price = avgPrice * 0.975;
                c.high_price = rangeHighTarget;  // range high target = avgPrice * 0.98
                c.low_price = avgPrice * 0.95;
            } else {
                // 진입 후 캔들 또는 마지막 캔들: 매수가 위
                c.opening_price = activePrice;
                c.trade_price = activePrice;
                c.high_price = activePrice * 1.001;
                c.low_price = activePrice * 0.999;
            }
            c.candle_acc_trade_volume = 1000;
            candles.add(c);
        }
        return candles;
    }

    /** 포지션 생성 (openedAt = N시간 전) */
    private PositionEntity buildPosition(double avgPrice, long hoursAgo) {
        PositionEntity pe = new PositionEntity();
        pe.setMarket("KRW-TEST");
        pe.setQty(1.0);
        pe.setAvgPrice(avgPrice);
        pe.setAddBuys(0);
        pe.setOpenedAt(Instant.now().minusSeconds(hoursAgo * 3600L));
        pe.setEntryStrategy("SCALP_OPENING_BREAK");
        return pe;
    }

    /**
     * evaluate 실행 결과의 sellType 반환.
     * @param hoursSinceEntry 매수 후 경과 시간 (시간)
     */
    private String runScenario(int hourKst, int minKst, int dayOffset,
                                double avgPrice, long hoursSinceEntry) {
        List<UpbitCandle> candles = buildCandles(hourKst, minKst, dayOffset, avgPrice);
        PositionEntity pos = buildPosition(avgPrice, hoursSinceEntry);
        StrategyContext ctx = new StrategyContext("KRW-TEST", 5, candles, pos, 0);
        Signal sig = strategy.evaluate(ctx);
        if (sig.action == SignalAction.SELL) {
            return sig.reason;
        }
        return null;
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 1: 오버나잇 세션 — 09:30 진입 시간대 → 청산 X
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("시나리오 1: 09:30 KST (진입 시간대, 매수 0시간 경과) → SESSION_END 청산 안 됨")
    public void scenario1_inEntryHours() {
        // 매수 직후 09:30 평가 → elapsed=0시간 → 5시간 미달 → 청산 안 됨
        String reason = runScenario(9, 30, 0, 100.0, 0);
        if (reason != null) {
            assertFalse(reason.contains("OPEN_TIME_EXIT"),
                    "09:30 진입 직후는 SESSION_END 청산 안 되어야 함, reason=" + reason);
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 2: 오버나잇 세션 — 14:00 KST (당일 오후) → 청산 X
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("시나리오 2: 14:00 KST (당일 오후, 매수 5시간 후) → 청산 안 됨 (윈도우 밖)")
    public void scenario2_afternoon() {
        String reason = runScenario(14, 0, 0, 100.0, 5);
        if (reason != null) {
            assertFalse(reason.contains("OPEN_TIME_EXIT"),
                    "14:00은 청산 윈도우(08:50~10:00) 밖, reason=" + reason);
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 3: 오버나잇 세션 — 22:00 KST (당일 야간) → 청산 X
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("시나리오 3: 22:00 KST (당일 야간, 매수 13시간 후) → 청산 안 됨 (윈도우 밖)")
    public void scenario3_night() {
        String reason = runScenario(22, 0, 0, 100.0, 13);
        if (reason != null) {
            assertFalse(reason.contains("OPEN_TIME_EXIT"),
                    "22:00은 청산 윈도우 밖, reason=" + reason);
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 4: 오버나잇 세션 — 01:00 KST (익일 새벽) → 청산 X
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("시나리오 4: 01:00 KST (익일 새벽, 매수 16시간 후) → 청산 안 됨 (윈도우 밖)")
    public void scenario4_dawn() {
        String reason = runScenario(1, 0, 1, 100.0, 16);
        if (reason != null) {
            assertFalse(reason.contains("OPEN_TIME_EXIT"),
                    "01:00은 청산 윈도우 밖, reason=" + reason);
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 5: 오버나잇 세션 — 익일 08:50 KST → 청산 발동 ⭐
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("시나리오 5: 익일 08:50 KST (매수 23시간 후) → SESSION_END 청산 발동")
    public void scenario5_sessionEndExact() {
        String reason = runScenario(8, 50, 1, 100.0, 23);
        assertNotNull(reason, "08:50 정각 + 23시간 경과 → 청산 발동");
        assertTrue(reason.contains("OPEN_TIME_EXIT"),
                "OPEN_TIME_EXIT 청산 발동, reason=" + reason);
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 6: 오버나잇 세션 — 익일 09:30 KST → 청산 발동
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("시나리오 6: 익일 09:30 KST (매수 24시간 후) → SESSION_END 청산 발동")
    public void scenario6_inExitWindow() {
        String reason = runScenario(9, 30, 1, 100.0, 24);
        assertNotNull(reason);
        assertTrue(reason.contains("OPEN_TIME_EXIT"),
                "익일 09:30 + 24시간 경과 → 청산 발동, reason=" + reason);
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 7: 오버나잇 세션 — 익일 10:00 KST → 청산 안 됨 (윈도우 종료)
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("시나리오 7: 익일 10:00 KST (매수 25시간 후) → 윈도우 종료, 청산 안 됨")
    public void scenario7_afterExitWindow() {
        String reason = runScenario(10, 0, 1, 100.0, 25);
        if (reason != null) {
            assertFalse(reason.contains("OPEN_TIME_EXIT"),
                    "10:00은 청산 윈도우 종료 후, reason=" + reason);
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 8: 정상 세션 (12:00) — 13:00 KST → 청산 발동
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("시나리오 8: sessionEnd=12:00 (정상 세션) + 13:00 KST → 청산 발동")
    public void scenario8_normalSessionExit() {
        // 정상 세션 (12:00 = 당일 청산, 오버나잇 아님)
        ScalpOpeningBreakStrategy normalStrategy = new ScalpOpeningBreakStrategy()
                .withTiming(8, 0, 8, 59, 9, 0, 10, 30, 12, 0)
                .withRisk(1.5, 5.0, 0.7)
                .withFilters(1.5, 0.45);

        List<UpbitCandle> candles = buildCandles(13, 0, 0, 100.0);
        PositionEntity pos = buildPosition(100.0, 4); // 매수 4시간 전
        StrategyContext ctx = new StrategyContext("KRW-TEST", 5, candles, pos, 0);
        Signal sig = normalStrategy.evaluate(ctx);

        assertEquals(SignalAction.SELL, sig.action);
        assertTrue(sig.reason.contains("OPEN_TIME_EXIT"),
                "13:00은 정상 sessionEnd 12:00 이후 → 청산 발동");
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 9: 정상 세션 (12:00) — 11:00 KST → 청산 안 됨
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("시나리오 9: sessionEnd=12:00 + 11:00 KST → 청산 안 됨")
    public void scenario9_normalSessionBefore() {
        ScalpOpeningBreakStrategy normalStrategy = new ScalpOpeningBreakStrategy()
                .withTiming(8, 0, 8, 59, 9, 0, 10, 30, 12, 0)
                .withRisk(1.5, 5.0, 0.7)
                .withFilters(1.5, 0.45);

        List<UpbitCandle> candles = buildCandles(11, 0, 0, 100.0);
        PositionEntity pos = buildPosition(100.0, 2); // 매수 2시간 전
        StrategyContext ctx = new StrategyContext("KRW-TEST", 5, candles, pos, 0);
        Signal sig = normalStrategy.evaluate(ctx);

        if (sig.action == SignalAction.SELL && sig.reason != null) {
            assertFalse(sig.reason.contains("OPEN_TIME_EXIT"),
                    "11:00은 sessionEnd 12:00 전 → 청산 안 됨, reason=" + sig.reason);
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 10: 오버나잇 세션 경계 검증 (08:49 vs 08:50)
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("시나리오 10: 08:49 KST (매수 23시간 후) → 청산 안 됨 (08:50 전)")
    public void scenario10_oneMinuteBefore() {
        String reason = runScenario(8, 49, 1, 100.0, 23);
        if (reason != null) {
            assertFalse(reason.contains("OPEN_TIME_EXIT"),
                    "08:49는 sessionEnd 08:50 전 → 청산 안 됨, reason=" + reason);
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 11 (추가): 사용자 우려 핵심 — 09:30 진입 직후 즉시 evaluate → 청산 안 됨
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("시나리오 11: ⭐ 09:30 매수 직후 즉시 evaluate → SESSION_END 청산 안 됨 (당일 매수 보호)")
    public void scenario11_buyAndImmediateEvaluate() {
        // 운영서버에 발생할 수 있는 시나리오:
        // 09:30 매수 → 즉시 evaluate → SESSION_END 윈도우 안이지만 elapsed=0
        String reason = runScenario(9, 30, 0, 100.0, 0);
        if (reason != null) {
            assertFalse(reason.contains("OPEN_TIME_EXIT"),
                    "⚠️ 매수 직후 09:30 평가에서 SESSION_END 즉시 청산되면 안 됨!");
        }
    }
}
