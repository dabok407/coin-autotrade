package com.example.upbit.strategy;

import com.example.upbit.db.PositionEntity;
import com.example.upbit.market.UpbitCandle;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for HighConfidenceBreakoutStrategy (Strategy #21) - 6-Factor v2.
 *
 * Covers:
 *  - Hard Gates: VOL_GATE, DAY_GATE, BO_GATE, NOT_BULLISH, DOWNTREND
 *  - 6-Factor scoring tiers with boundary values
 *  - candleSurge vs breakoutPct hybrid logic
 *  - Exit mechanisms: HC_SL, HC_EMA_BREAK, HC_MACD_FADE, HC_TRAIL, HC_TIME_STOP, HC_SESSION_END
 *  - Grace period protection
 *  - Exit flag configuration
 *  - Edge cases
 */
public class HighConfidenceBreakoutStrategyTest {

    private HighConfidenceBreakoutStrategy strategy;

    @BeforeEach
    public void setUp() {
        strategy = new HighConfidenceBreakoutStrategy()
                .withRisk(1.5, 0.8)
                .withFilters(3.0, 0.60, 9.4)
                .withTimeStop(12, 0.3);
    }

    // ===== Candle Data Helpers =====

    private List<UpbitCandle> buildCandles(int count, double basePrice, double stepUp, double lastVolume) {
        List<UpbitCandle> candles = new ArrayList<UpbitCandle>();
        ZonedDateTime startTime = ZonedDateTime.of(2026, 4, 7, 1, 0, 0, 0, ZoneOffset.UTC);
        for (int i = 0; i < count; i++) {
            UpbitCandle c = new UpbitCandle();
            c.market = "KRW-TEST";
            ZonedDateTime candleTime = startTime.plusMinutes((long) i * 5);
            c.candle_date_time_utc = candleTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
            double open = basePrice + i * stepUp;
            double close = open + stepUp * 0.8;
            c.opening_price = open;
            c.trade_price = close;
            c.high_price = close + stepUp * 0.05;
            c.low_price = open - stepUp * 0.05;
            c.candle_acc_trade_volume = 1000;
            candles.add(c);
        }
        candles.get(count - 1).candle_acc_trade_volume = lastVolume;
        return candles;
    }

    private List<UpbitCandle> buildTrendingUpCandles(int count, double basePrice, double stepUp) {
        List<UpbitCandle> candles = new ArrayList<UpbitCandle>();
        ZonedDateTime startTime = ZonedDateTime.of(2026, 4, 7, 1, 0, 0, 0, ZoneOffset.UTC);
        for (int i = 0; i < count; i++) {
            UpbitCandle c = new UpbitCandle();
            c.market = "KRW-TEST";
            ZonedDateTime candleTime = startTime.plusMinutes((long) i * 5);
            c.candle_date_time_utc = candleTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
            double open = basePrice + i * stepUp;
            double close = open + stepUp * 0.8;
            c.opening_price = open;
            c.trade_price = close;
            c.high_price = close + stepUp * 0.1;
            c.low_price = open - stepUp * 0.1;
            c.candle_acc_trade_volume = 5000 + i * 100;
            candles.add(c);
        }
        return candles;
    }

    private List<UpbitCandle> buildFlatCandles(int count, double price) {
        List<UpbitCandle> candles = new ArrayList<UpbitCandle>();
        // 마지막 캔들이 현재 시각과 가깝도록 (테스트가 시간에 독립적이어야 함)
        ZonedDateTime startTime = ZonedDateTime.now(ZoneOffset.UTC).minusMinutes((long) count * 5);
        for (int i = 0; i < count; i++) {
            UpbitCandle c = new UpbitCandle();
            c.market = "KRW-TEST";
            ZonedDateTime candleTime = startTime.plusMinutes((long) i * 5);
            c.candle_date_time_utc = candleTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
            c.opening_price = price - 1;
            c.trade_price = price + 1;
            c.high_price = price + 3;
            c.low_price = price - 3;
            c.candle_acc_trade_volume = 1000;
            candles.add(c);
        }
        return candles;
    }

    private List<UpbitCandle> buildEntryReadyCandles(double lastVolume) {
        List<UpbitCandle> candles = new ArrayList<UpbitCandle>();
        ZonedDateTime startTime = ZonedDateTime.of(2026, 4, 7, 1, 0, 0, 0, ZoneOffset.UTC);
        double basePrice = 50000;
        double step = 30;
        for (int i = 0; i < 80; i++) {
            UpbitCandle c = new UpbitCandle();
            c.market = "KRW-TEST";
            c.candle_date_time_utc = startTime.plusMinutes((long) i * 5)
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
            double open = basePrice + i * step;
            double close = open + step * 0.8;
            c.opening_price = open;
            c.trade_price = close;
            c.high_price = close + step * 0.05;
            c.low_price = open - step * 0.05;
            c.candle_acc_trade_volume = 1000;
            candles.add(c);
        }
        UpbitCandle last = candles.get(79);
        double prevHigh = 0;
        for (int i = 59; i < 79; i++) {
            prevHigh = Math.max(prevHigh, candles.get(i).high_price);
        }
        double boClose = prevHigh * 1.02;
        last.opening_price = boClose - 100;
        last.trade_price = boClose;
        last.high_price = boClose + 10;
        last.low_price = last.opening_price - 10;
        last.candle_acc_trade_volume = lastVolume;
        return candles;
    }

    private StrategyContext entryContext(List<UpbitCandle> candles) {
        return new StrategyContext("KRW-TEST", 5, candles, null, 0);
    }

    private StrategyContext entryContextWithDailyOpen(List<UpbitCandle> candles, double dailyOpenPrice) {
        return new StrategyContext("KRW-TEST", 5, candles, null, 0,
                Collections.<String, Integer>emptyMap(), dailyOpenPrice);
    }

    private StrategyContext exitContext(List<UpbitCandle> candles, PositionEntity position) {
        return new StrategyContext("KRW-TEST", 5, candles, position, 0);
    }

    private PositionEntity buildPosition(double avgPrice, double qty, Instant openedAt) {
        PositionEntity pe = new PositionEntity();
        pe.setMarket("KRW-TEST");
        pe.setQty(qty);
        pe.setAvgPrice(avgPrice);
        pe.setAddBuys(0);
        pe.setOpenedAt(openedAt);
        pe.setEntryStrategy("HIGH_CONFIDENCE_BREAKOUT");
        return pe;
    }

    // ============================================================
    //  Hard Gate Tests
    // ============================================================

    @Test
    public void gate_bearishCandle_blocked() {
        List<UpbitCandle> candles = buildTrendingUpCandles(80, 50000, 50);
        UpbitCandle last = candles.get(candles.size() - 1);
        double origClose = last.trade_price;
        last.trade_price = last.opening_price - 100;
        last.opening_price = origClose + 100;

        Signal signal = strategy.evaluate(entryContext(candles));
        assertEquals(SignalAction.NONE, signal.action);
        assertTrue(signal.reason.contains("NOT_BULLISH"), "reason=" + signal.reason);
    }

    @Test
    public void gate_volBelowMult_blocked() {
        // 2026-04-18 1안 강화: gate = volumeSurgeMult (×0.5 제거)
        // DB=3.0 → gate=3.0x. vol=1500 → ratio~1.46x → BLOCKED
        List<UpbitCandle> candles = buildCandles(80, 50000, 50, 1500);

        HighConfidenceBreakoutStrategy s = new HighConfidenceBreakoutStrategy()
                .withFilters(3.0, 0.0, 1.0);

        Signal signal = s.evaluate(entryContextWithDailyOpen(candles, 49000));
        assertEquals(SignalAction.NONE, signal.action);
        assertTrue(signal.reason.contains("VOL_GATE"), "reason=" + signal.reason);
    }

    @Test
    public void gate_volAboveMult_passes() {
        // 2026-04-18 1안 강화: gate = volumeSurgeMult 그대로
        // SMA includes last candle. 19 candles at 1000 + last at 3500, SMA=(19000+3500)/20=1125, ratio=3500/1125~3.11x
        // DB=3.0 → gate=3.0x → passes
        List<UpbitCandle> candles = buildCandles(80, 50000, 50, 3500);

        HighConfidenceBreakoutStrategy s = new HighConfidenceBreakoutStrategy()
                .withFilters(3.0, 0.0, 1.0);

        Signal signal = s.evaluate(entryContextWithDailyOpen(candles, 49000));
        if (signal.action == SignalAction.NONE) {
            assertFalse(signal.reason.contains("VOL_GATE"),
                    "~3.1x should pass VOL_GATE (gate=3.0), reason=" + signal.reason);
        }
    }

    @Test
    public void gate_dailyChangeNegative_blocked() {
        List<UpbitCandle> candles = buildCandles(80, 50000, 50, 10000);
        double lastClose = candles.get(candles.size() - 1).trade_price;

        HighConfidenceBreakoutStrategy s = new HighConfidenceBreakoutStrategy()
                .withFilters(3.0, 0.0, 1.0);

        Signal signal = s.evaluate(entryContextWithDailyOpen(candles, lastClose + 1000));
        assertEquals(SignalAction.NONE, signal.action);
        assertTrue(signal.reason.contains("DAY_GATE"), "reason=" + signal.reason);
    }

    @Test
    public void gate_dailyChangeZero_blocked() {
        List<UpbitCandle> candles = buildCandles(80, 50000, 50, 10000);
        double lastClose = candles.get(candles.size() - 1).trade_price;

        HighConfidenceBreakoutStrategy s = new HighConfidenceBreakoutStrategy()
                .withFilters(3.0, 0.0, 1.0);

        Signal signal = s.evaluate(entryContextWithDailyOpen(candles, lastClose));
        assertEquals(SignalAction.NONE, signal.action);
        assertTrue(signal.reason.contains("DAY_GATE"), "reason=" + signal.reason);
    }

    @Test
    public void gate_noBreakout_blocked() {
        List<UpbitCandle> candles = buildFlatCandles(80, 50000);
        UpbitCandle last = candles.get(candles.size() - 1);
        last.opening_price = 49999;
        last.trade_price = 50001;
        last.high_price = 50002;
        last.low_price = 49998;
        last.candle_acc_trade_volume = 10000;

        HighConfidenceBreakoutStrategy s = new HighConfidenceBreakoutStrategy()
                .withFilters(3.0, 0.0, 1.0);

        Signal signal = s.evaluate(entryContextWithDailyOpen(candles, 49500));
        assertEquals(SignalAction.NONE, signal.action);
        assertTrue(signal.reason.contains("BO_GATE"), "reason=" + signal.reason);
    }

    @Test
    public void gate_downtrend_blocked() {
        List<UpbitCandle> candles = new ArrayList<UpbitCandle>();
        ZonedDateTime startTime = ZonedDateTime.of(2026, 4, 7, 1, 0, 0, 0, ZoneOffset.UTC);
        for (int i = 0; i < 80; i++) {
            UpbitCandle c = new UpbitCandle();
            c.market = "KRW-TEST";
            c.candle_date_time_utc = startTime.plusMinutes((long) i * 5)
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
            double price = 60000 - i * 200;
            c.opening_price = price + 20;
            c.trade_price = price - 20;
            c.high_price = price + 30;
            c.low_price = price - 30;
            c.candle_acc_trade_volume = 5000;
            candles.add(c);
        }
        UpbitCandle last = candles.get(79);
        last.opening_price = last.trade_price;
        last.trade_price = last.opening_price + 300;
        last.high_price = last.trade_price + 50;
        last.low_price = last.opening_price - 10;
        last.candle_acc_trade_volume = 50000;

        Signal signal = strategy.evaluate(entryContext(candles));
        assertEquals(SignalAction.NONE, signal.action);
        assertTrue(signal.reason.contains("DOWNTREND"), "reason=" + signal.reason);
    }

    @Test
    public void gate_sideways_passesDowntrend() {
        List<UpbitCandle> candles = buildFlatCandles(80, 50000);
        UpbitCandle last = candles.get(79);
        last.opening_price = 50000;
        last.trade_price = 51000;
        last.high_price = 51100;
        last.low_price = 49900;
        last.candle_acc_trade_volume = 50000;

        Signal signal = strategy.evaluate(entryContext(candles));
        if (signal.action == SignalAction.NONE && signal.reason != null) {
            assertFalse(signal.reason.contains("DOWNTREND"),
                    "Sideways should not trigger DOWNTREND, reason=" + signal.reason);
        }
    }

    @Test
    public void gate_insufficientCandles_none() {
        List<UpbitCandle> candles = buildTrendingUpCandles(30, 50000, 50);
        Signal signal = strategy.evaluate(entryContext(candles));
        assertEquals(SignalAction.NONE, signal.action);
    }

    // ============================================================
    //  Scoring Tier Tests
    // ============================================================

    @Test
    public void scoring_volAboveMult_passesGate() {
        // 2026-04-18 1안 강화 반영: gate=volumeSurgeMult 그대로 → 3.0x 이상 필요
        // vol=4000, avg(20)=1150, ratio~3.48x → passes
        List<UpbitCandle> candles = buildEntryReadyCandles(4000);
        HighConfidenceBreakoutStrategy s = new HighConfidenceBreakoutStrategy()
                .withFilters(3.0, 0.0, 1.0);
        double lastClose = candles.get(79).trade_price;
        Signal signal = s.evaluate(entryContextWithDailyOpen(candles, lastClose * 0.98));
        if (signal.action == SignalAction.NONE) {
            assertFalse(signal.reason.contains("VOL_GATE"), "reason=" + signal.reason);
        }
    }

    @Test
    public void scoring_vol10x_passesGate() {
        List<UpbitCandle> candles = buildEntryReadyCandles(12000);
        HighConfidenceBreakoutStrategy s = new HighConfidenceBreakoutStrategy()
                .withFilters(3.0, 0.0, 1.0);
        double lastClose = candles.get(79).trade_price;
        Signal signal = s.evaluate(entryContextWithDailyOpen(candles, lastClose * 0.98));
        if (signal.action == SignalAction.NONE) {
            assertFalse(signal.reason.contains("VOL_GATE"), "reason=" + signal.reason);
        }
    }

    @Test
    public void scoring_dailyChange_lowTier() {
        List<UpbitCandle> candles = buildEntryReadyCandles(5000);
        double lastClose = candles.get(79).trade_price;
        double dailyOpen = lastClose / 1.001; // 0.1% change

        HighConfidenceBreakoutStrategy s = new HighConfidenceBreakoutStrategy()
                .withFilters(3.0, 0.0, 1.0);

        Signal signal = s.evaluate(entryContextWithDailyOpen(candles, dailyOpen));
        if (signal.action == SignalAction.NONE) {
            assertFalse(signal.reason.contains("DAY_GATE"), "reason=" + signal.reason);
        }
    }

    @Test
    public void scoring_dailyChange_peakTier() {
        List<UpbitCandle> candles = buildEntryReadyCandles(5000);
        double lastClose = candles.get(79).trade_price;
        double dailyOpen = lastClose / 1.02; // 2% change (peak tier)

        HighConfidenceBreakoutStrategy s = new HighConfidenceBreakoutStrategy()
                .withFilters(3.0, 0.0, 1.0);

        Signal signal = s.evaluate(entryContextWithDailyOpen(candles, dailyOpen));
        if (signal.action == SignalAction.BUY) {
            assertTrue(signal.reason.contains("day=+"), "reason=" + signal.reason);
        }
        if (signal.action == SignalAction.NONE) {
            assertFalse(signal.reason.contains("DAY_GATE"), "reason=" + signal.reason);
        }
    }

    @Test
    public void scoring_dailyChange_runawayTier() {
        List<UpbitCandle> candles = buildEntryReadyCandles(5000);
        double lastClose = candles.get(79).trade_price;
        double dailyOpen = lastClose / 1.35; // 35% change

        HighConfidenceBreakoutStrategy s = new HighConfidenceBreakoutStrategy()
                .withFilters(3.0, 0.0, 1.0);

        Signal signal = s.evaluate(entryContextWithDailyOpen(candles, dailyOpen));
        if (signal.action == SignalAction.NONE) {
            assertFalse(signal.reason.contains("DAY_GATE"), "reason=" + signal.reason);
        }
    }

    @Test
    public void scoring_rsiValueInRange() {
        List<UpbitCandle> candles = buildEntryReadyCandles(5000);
        double rsi = Indicators.rsi(candles, 14);
        assertTrue(rsi >= 0 && rsi <= 100, "RSI should be 0-100, got " + rsi);
    }

    @Test
    public void scoring_bodyQualityHigh() {
        List<UpbitCandle> candles = buildEntryReadyCandles(5000);
        UpbitCandle last = candles.get(79);
        double range = 100;
        last.low_price = last.trade_price - range;
        last.high_price = last.trade_price;
        last.opening_price = last.trade_price - range * 0.90;
        double bodyRatio = CandlePatterns.body(last) / CandlePatterns.range(last);
        assertTrue(bodyRatio >= 0.70, "bodyRatio should be >= 0.70, got " + bodyRatio);
    }

    @Test
    public void scoring_bodyQualityLow() {
        List<UpbitCandle> candles = buildEntryReadyCandles(5000);
        UpbitCandle last = candles.get(79);
        double mid = last.trade_price;
        last.high_price = mid + 50;
        last.low_price = mid - 50;
        last.opening_price = mid - 10;
        double bodyRatio = CandlePatterns.body(last) / CandlePatterns.range(last);
        assertTrue(bodyRatio < 0.30, "bodyRatio should be < 0.30, got " + bodyRatio);
    }

    // ============================================================
    //  candleSurge vs breakoutPct Hybrid
    // ============================================================

    @Test
    public void hybrid_candleSurgeDominates() {
        List<UpbitCandle> candles = buildTrendingUpCandles(80, 50000, 30);
        UpbitCandle last = candles.get(79);
        UpbitCandle prev = candles.get(78);
        double recentHigh = 0;
        for (int i = 59; i < 79; i++) {
            recentHigh = Math.max(recentHigh, candles.get(i).high_price);
        }
        double boClose = recentHigh * 1.001; // tiny breakout
        prev.trade_price = boClose / 1.025;  // large surge
        last.trade_price = boClose;
        last.opening_price = boClose - 50;
        last.high_price = boClose + 10;
        last.low_price = last.opening_price - 10;
        last.candle_acc_trade_volume = 10000;

        HighConfidenceBreakoutStrategy s = new HighConfidenceBreakoutStrategy()
                .withFilters(3.0, 0.0, 1.0);

        Signal signal = s.evaluate(entryContextWithDailyOpen(candles, boClose * 0.98));
        if (signal.action == SignalAction.BUY) {
            assertTrue(signal.reason.contains("surge="), "reason=" + signal.reason);
        }
        if (signal.action == SignalAction.NONE) {
            assertFalse(signal.reason.contains("BO_GATE"), "reason=" + signal.reason);
        }
    }

    @Test
    public void hybrid_breakoutPctDominates() {
        List<UpbitCandle> candles = buildTrendingUpCandles(80, 50000, 30);
        UpbitCandle last = candles.get(79);
        UpbitCandle prev = candles.get(78);
        double recentHigh = 0;
        for (int i = 59; i < 79; i++) {
            recentHigh = Math.max(recentHigh, candles.get(i).high_price);
        }
        double boClose = recentHigh * 1.03; // 3% breakout
        last.trade_price = boClose;
        last.opening_price = boClose - 50;
        last.high_price = boClose + 10;
        last.low_price = last.opening_price - 10;
        prev.trade_price = boClose * 0.995; // small surge
        last.candle_acc_trade_volume = 10000;

        HighConfidenceBreakoutStrategy s = new HighConfidenceBreakoutStrategy()
                .withFilters(3.0, 0.0, 1.0);

        Signal signal = s.evaluate(entryContextWithDailyOpen(candles, boClose * 0.98));
        if (signal.action == SignalAction.BUY) {
            assertTrue(signal.reason.contains("bo="), "reason=" + signal.reason);
        }
    }

    @Test
    public void hybrid_bothNegative_blocked() {
        List<UpbitCandle> candles = buildFlatCandles(80, 50000);
        UpbitCandle last = candles.get(candles.size() - 1);
        last.opening_price = 49999;
        last.trade_price = 50000;
        last.high_price = 50001;
        last.low_price = 49998;
        last.candle_acc_trade_volume = 10000;

        HighConfidenceBreakoutStrategy s = new HighConfidenceBreakoutStrategy()
                .withFilters(3.0, 0.0, 1.0);

        Signal signal = s.evaluate(entryContextWithDailyOpen(candles, 49500));
        assertEquals(SignalAction.NONE, signal.action);
        assertTrue(signal.reason.contains("BO_GATE"), "reason=" + signal.reason);
    }

    // ============================================================
    //  Entry Integration Tests
    // ============================================================

    @Test
    public void entry_lowScore_blocked() {
        HighConfidenceBreakoutStrategy strict = new HighConfidenceBreakoutStrategy()
                .withFilters(3.0, 0.60, 10.1);

        List<UpbitCandle> candles = buildTrendingUpCandles(80, 50000, 80);
        candles.get(79).candle_acc_trade_volume = 50000;

        Signal signal = strict.evaluate(entryContextWithDailyOpen(candles, 49000));
        assertEquals(SignalAction.NONE, signal.action);
    }

    @Test
    public void entry_buySignalWithLowBar() {
        HighConfidenceBreakoutStrategy easy = new HighConfidenceBreakoutStrategy()
                .withFilters(3.0, 0.0, 1.0);

        List<UpbitCandle> candles = buildTrendingUpCandles(80, 50000, 80);
        double avgVol = 0;
        for (int i = 60; i < 80; i++) avgVol += candles.get(i).candle_acc_trade_volume;
        avgVol /= 20;
        candles.get(79).candle_acc_trade_volume = avgVol * 5.0;

        double lastClose = candles.get(79).trade_price;
        Signal signal = easy.evaluate(entryContextWithDailyOpen(candles, lastClose * 0.97));

        if (signal.action == SignalAction.BUY) {
            assertTrue(signal.confidence > 0, "confidence > 0");
            assertTrue(signal.confidence <= 10.0, "confidence <= 10.0");
            assertTrue(signal.reason.contains("HC_BREAK"), "reason contains HC_BREAK");
            assertTrue(signal.reason.contains("vol="), "reason contains vol=");
            assertTrue(signal.reason.contains("day="), "reason contains day=");
            assertTrue(signal.reason.contains("rsi="), "reason contains rsi=");
            assertTrue(signal.reason.contains("bo="), "reason contains bo=");
        }
    }

    @Test
    public void entry_strategyType() {
        assertEquals(StrategyType.HIGH_CONFIDENCE_BREAKOUT, strategy.type());
    }

    // ============================================================
    //  Exit Tests
    // ============================================================

    @Test
    public void exit_hardStopLoss() {
        double avgPrice = 50000;
        List<UpbitCandle> candles = buildTrendingUpCandles(80, 48000, 20);
        candles.get(79).trade_price = avgPrice * 0.98; // -2% PnL

        PositionEntity pos = buildPosition(avgPrice, 1.0, Instant.now().minusSeconds(300));
        Signal signal = strategy.evaluate(exitContext(candles, pos));

        assertEquals(SignalAction.SELL, signal.action);
        assertTrue(signal.reason.contains("HC_SL"), "reason=" + signal.reason);
    }

    @Test
    public void exit_hardSlBoundary() {
        double avgPrice = 50000;
        List<UpbitCandle> candles = buildTrendingUpCandles(80, 48000, 20);
        candles.get(79).trade_price = avgPrice * (1 - 0.015); // exactly -1.5%

        PositionEntity pos = buildPosition(avgPrice, 1.0, Instant.now().minusSeconds(300));
        Signal signal = strategy.evaluate(exitContext(candles, pos));

        assertEquals(SignalAction.SELL, signal.action);
        assertTrue(signal.reason.contains("HC_SL"), "reason=" + signal.reason);
    }

    @Test
    public void exit_hardSlNotTriggered() {
        double avgPrice = 50000;
        double closePrice = avgPrice * (1 - 0.014); // -1.4%
        List<UpbitCandle> candles = buildFlatCandles(80, closePrice);
        for (int i = 70; i < 80; i++) {
            candles.get(i).trade_price = closePrice + (i - 70) * 5;
            candles.get(i).opening_price = candles.get(i).trade_price - 1;
            candles.get(i).high_price = candles.get(i).trade_price + 2;
            candles.get(i).low_price = candles.get(i).opening_price - 2;
        }

        PositionEntity pos = buildPosition(avgPrice, 1.0, Instant.now().minusSeconds(300));
        Signal signal = strategy.evaluate(exitContext(candles, pos));

        if (signal.action == SignalAction.SELL && signal.reason != null) {
            assertFalse(signal.reason.contains("HC_SL"),
                    "-1.4% should not trigger HC_SL, reason=" + signal.reason);
        }
    }

    @Test
    public void exit_emaBreak() {
        double avgPrice = 50000;
        List<UpbitCandle> candles = buildTrendingUpCandles(80, 50000, 30);
        for (int i = 65; i < 80; i++) {
            UpbitCandle c = candles.get(i);
            double drop = (i - 65) * 150;
            c.opening_price = c.opening_price - drop;
            c.trade_price = c.opening_price - 80;
            c.high_price = c.opening_price + 20;
            c.low_price = c.trade_price - 20;
        }
        candles.get(79).trade_price = avgPrice + 100; // +0.2% PnL

        PositionEntity pos = buildPosition(avgPrice, 1.0, Instant.now().minusSeconds(300));
        Signal signal = strategy.evaluate(exitContext(candles, pos));

        assertEquals(SignalAction.SELL, signal.action);
        assertTrue(signal.reason.contains("HC_SL") || signal.reason.contains("HC_EMA_BREAK"),
                "reason=" + signal.reason);
    }

    @Test
    public void exit_macdFade() {
        double avgPrice = 50000;
        List<UpbitCandle> candles = buildTrendingUpCandles(80, 49000, 40);
        for (int i = 70; i < 80; i++) {
            UpbitCandle c = candles.get(i);
            c.opening_price = 52000 - (i - 70) * 30;
            c.trade_price = c.opening_price - 10;
            c.high_price = c.opening_price + 20;
            c.low_price = c.trade_price - 20;
        }
        candles.get(79).trade_price = avgPrice + 500;
        candles.get(79).opening_price = candles.get(79).trade_price + 10;

        PositionEntity pos = buildPosition(avgPrice, 1.0, Instant.now().minusSeconds(300));
        Signal signal = strategy.evaluate(exitContext(candles, pos));

        assertEquals(SignalAction.SELL, signal.action);
    }

    @Test
    public void exit_macdFadeOnlyInProfit() {
        double avgPrice = 55000;
        List<UpbitCandle> candles = buildTrendingUpCandles(80, 49000, 40);
        for (int i = 70; i < 80; i++) {
            UpbitCandle c = candles.get(i);
            c.opening_price = 52000 - (i - 70) * 30;
            c.trade_price = c.opening_price - 10;
            c.high_price = c.opening_price + 20;
            c.low_price = c.trade_price - 20;
        }
        candles.get(79).trade_price = avgPrice - 200; // loss

        PositionEntity pos = buildPosition(avgPrice, 1.0, Instant.now().minusSeconds(300));
        HighConfidenceBreakoutStrategy s = new HighConfidenceBreakoutStrategy()
                .withRisk(5.0, 0.8)
                .withExitFlags(false, true)
                .withTimeStop(0, 0);

        Signal signal = s.evaluate(exitContext(candles, pos));
        if (signal.action == SignalAction.SELL && signal.reason != null) {
            assertFalse(signal.reason.contains("HC_MACD_FADE"),
                    "MACD fade should not trigger in loss, reason=" + signal.reason);
        }
    }

    @Test
    public void exit_trailingStop() {
        double avgPrice = 50000;
        List<UpbitCandle> candles = buildTrendingUpCandles(80, 49500, 50);

        // Push peak high in candles 55-65: price goes to ~53000
        for (int i = 55; i < 65; i++) {
            UpbitCandle c = candles.get(i);
            c.high_price = avgPrice + 3000 + (i - 55) * 100;
            c.trade_price = c.high_price - 50;
            c.opening_price = c.trade_price - 100;
            c.low_price = c.opening_price - 50;
        }
        // Pull back to just above avgPrice (still in profit but below trail stop)
        // Last close must still be > avgPrice for pnlPct > trailActivatePct
        for (int i = 65; i < 80; i++) {
            UpbitCandle c = candles.get(i);
            c.opening_price = avgPrice + 300;
            c.trade_price = avgPrice + 250;  // +0.5% profit, trail should activate
            c.high_price = avgPrice + 350;
            c.low_price = avgPrice + 200;
        }

        // openedAt must be BEFORE candle timestamps (candles start at 2026-04-07T01:00 UTC)
        // Set openedAt to candle[50] time so peak candles (55-65) are after entry
        Instant openedAt = ZonedDateTime.of(2026, 4, 7, 1, 0, 0, 0, ZoneOffset.UTC)
                .plusMinutes(50 * 5).toInstant(); // candle[50] time

        PositionEntity pos = buildPosition(avgPrice, 1.0, openedAt);
        HighConfidenceBreakoutStrategy s = new HighConfidenceBreakoutStrategy()
                .withRisk(5.0, 0.8)         // wide SL
                .withTrailActivate(0.1)      // low activation
                .withExitFlags(false, false)  // no EMA/MACD
                .withTimeStop(0, 0);          // no time stop

        Signal signal = s.evaluate(exitContext(candles, pos));
        assertEquals(SignalAction.SELL, signal.action);
        assertTrue(signal.reason.contains("HC_TRAIL"), "reason=" + signal.reason);
    }

    @Test
    public void exit_trailingNotActiveWhenLowPnl() {
        double avgPrice = 50000;
        double closePrice = avgPrice * 1.002; // +0.2%

        List<UpbitCandle> candles = buildFlatCandles(80, closePrice);
        for (int i = 0; i < 80; i++) {
            candles.get(i).trade_price = closePrice + i * 0.5;
            candles.get(i).opening_price = candles.get(i).trade_price - 1;
            candles.get(i).high_price = candles.get(i).trade_price + 2;
            candles.get(i).low_price = candles.get(i).opening_price - 2;
        }
        candles.get(79).trade_price = closePrice;

        PositionEntity pos = buildPosition(avgPrice, 1.0, Instant.now().minusSeconds(60));
        HighConfidenceBreakoutStrategy s = new HighConfidenceBreakoutStrategy()
                .withRisk(5.0, 0.8)
                .withTrailActivate(0.5)
                .withExitFlags(false, false)
                .withTimeStop(0, 0);

        Signal signal = s.evaluate(exitContext(candles, pos));
        if (signal.action == SignalAction.SELL && signal.reason != null) {
            assertFalse(signal.reason.contains("HC_TRAIL"),
                    "Trailing should not activate at 0.2%, reason=" + signal.reason);
        }
    }

    @Test
    public void exit_timeStop() {
        double avgPrice = 50000;
        double closePrice = avgPrice * 1.001; // +0.1%

        List<UpbitCandle> candles = buildFlatCandles(80, closePrice);
        for (int i = 0; i < 80; i++) {
            candles.get(i).trade_price = closePrice + (i * 0.5);
            candles.get(i).opening_price = candles.get(i).trade_price - 1;
            candles.get(i).high_price = candles.get(i).trade_price + 2;
            candles.get(i).low_price = candles.get(i).opening_price - 2;
        }
        candles.get(79).trade_price = closePrice;

        Instant openedAt = Instant.now().minusSeconds(80 * 5 * 60);
        PositionEntity pos = buildPosition(avgPrice, 1.0, openedAt);
        HighConfidenceBreakoutStrategy s = new HighConfidenceBreakoutStrategy()
                .withRisk(5.0, 0.8)
                .withExitFlags(false, false)
                .withTimeStop(12, 0.3);

        Signal signal = s.evaluate(exitContext(candles, pos));
        assertEquals(SignalAction.SELL, signal.action);
        assertTrue(signal.reason.contains("HC_TIME_STOP"), "reason=" + signal.reason);
    }

    @Test
    public void exit_timeStopNotInProfit() {
        double avgPrice = 50000;
        double closePrice = avgPrice * 1.005; // +0.5%

        List<UpbitCandle> candles = buildFlatCandles(80, closePrice);
        for (int i = 0; i < 80; i++) {
            candles.get(i).trade_price = closePrice + i * 0.5;
            candles.get(i).opening_price = candles.get(i).trade_price - 1;
            candles.get(i).high_price = candles.get(i).trade_price + 2;
            candles.get(i).low_price = candles.get(i).opening_price - 2;
        }
        candles.get(79).trade_price = closePrice;

        Instant openedAt = Instant.now().minusSeconds(80 * 5 * 60);
        PositionEntity pos = buildPosition(avgPrice, 1.0, openedAt);
        HighConfidenceBreakoutStrategy s = new HighConfidenceBreakoutStrategy()
                .withRisk(5.0, 0.8)
                .withExitFlags(false, false)
                .withTrailActivate(10.0)
                .withTimeStop(12, 0.3);

        Signal signal = s.evaluate(exitContext(candles, pos));
        if (signal.action == SignalAction.SELL && signal.reason != null) {
            assertFalse(signal.reason.contains("HC_TIME_STOP"),
                    "Time stop should not trigger at 0.5%, reason=" + signal.reason);
        }
    }

    @Test
    public void exit_sessionEndOvernight() {
        double avgPrice = 50000;
        List<UpbitCandle> candles = buildFlatCandles(80, avgPrice + 100);
        candles.get(79).candle_date_time_utc = "2026-04-06T23:30:00"; // 08:30 KST
        candles.get(79).trade_price = avgPrice + 100;

        PositionEntity pos = buildPosition(avgPrice, 1.0, Instant.now().minusSeconds(300));
        HighConfidenceBreakoutStrategy s = new HighConfidenceBreakoutStrategy()
                .withRisk(5.0, 0.8)
                .withExitFlags(false, false) // disable EMA/MACD to isolate session end
                .withTrailActivate(10.0)
                .withTimeStop(0, 0)
                .withTiming(8, 0);

        Signal signal = s.evaluate(exitContext(candles, pos));
        assertEquals(SignalAction.SELL, signal.action);
        assertTrue(signal.reason.contains("HC_SESSION_END"), "reason=" + signal.reason);
    }

    @Test
    public void exit_sessionEndNotAfterWindow() {
        double avgPrice = 50000;
        double closePrice = avgPrice + 100;
        List<UpbitCandle> candles = buildFlatCandles(80, closePrice);
        candles.get(79).candle_date_time_utc = "2026-04-07T01:30:00"; // 10:30 KST
        candles.get(79).trade_price = closePrice;
        for (int i = 70; i < 80; i++) {
            candles.get(i).trade_price = closePrice + (i - 70) * 5;
            candles.get(i).opening_price = candles.get(i).trade_price - 1;
            candles.get(i).high_price = candles.get(i).trade_price + 2;
            candles.get(i).low_price = candles.get(i).opening_price - 2;
        }

        PositionEntity pos = buildPosition(avgPrice, 1.0, Instant.now().minusSeconds(300));
        HighConfidenceBreakoutStrategy s = new HighConfidenceBreakoutStrategy()
                .withRisk(5.0, 0.8)
                .withExitFlags(false, false)
                .withTrailActivate(10.0)
                .withTimeStop(0, 0)
                .withTiming(8, 0);

        Signal signal = s.evaluate(exitContext(candles, pos));
        if (signal.action == SignalAction.SELL && signal.reason != null) {
            assertFalse(signal.reason.contains("HC_SESSION_END"),
                    "10:30 KST should not trigger session end, reason=" + signal.reason);
        }
    }

    // ============================================================
    //  Grace Period Tests
    // ============================================================

    @Test
    public void gracePeriod_protectsFromNonSlExits() {
        double avgPrice = 50000;
        List<UpbitCandle> candles = buildTrendingUpCandles(80, 50000, 30);
        for (int i = 65; i < 80; i++) {
            UpbitCandle c = candles.get(i);
            double drop = (i - 65) * 100;
            c.opening_price = c.opening_price - drop;
            c.trade_price = c.opening_price - 50;
            c.high_price = c.opening_price + 20;
            c.low_price = c.trade_price - 20;
        }
        candles.get(79).trade_price = avgPrice - 200; // -0.4% (above -1.5% SL)

        // openedAt just before the last candle (candle[78] time) so candlesSinceEntry=1
        // Candles start at 2026-04-07T01:00 UTC, 5min intervals, so candle[78] is at +390min
        Instant openedAt = ZonedDateTime.of(2026, 4, 7, 1, 0, 0, 0, ZoneOffset.UTC)
                .plusMinutes(78 * 5).toInstant();
        PositionEntity pos = buildPosition(avgPrice, 1.0, openedAt);
        HighConfidenceBreakoutStrategy s = new HighConfidenceBreakoutStrategy()
                .withRisk(1.5, 0.8)
                .withGracePeriod(5) // 5 candle grace, candlesSinceEntry=1 <= 5
                .withTimeStop(12, 0.3)
                .withTiming(23, 59);

        Signal signal = s.evaluate(exitContext(candles, pos));
        if (signal.action == SignalAction.SELL && signal.reason != null) {
            assertTrue(signal.reason.contains("HC_SL") || signal.reason.contains("HC_SESSION_END"),
                    "During grace, only SL and SESSION_END, reason=" + signal.reason);
        }
    }

    @Test
    public void gracePeriod_slStillFires() {
        double avgPrice = 50000;
        List<UpbitCandle> candles = buildFlatCandles(80, avgPrice * 0.97);
        candles.get(79).trade_price = avgPrice * 0.97; // -3%

        Instant openedAt = Instant.now().minusSeconds(30);
        PositionEntity pos = buildPosition(avgPrice, 1.0, openedAt);
        HighConfidenceBreakoutStrategy s = new HighConfidenceBreakoutStrategy()
                .withRisk(1.5, 0.8)
                .withGracePeriod(10);

        Signal signal = s.evaluate(exitContext(candles, pos));
        assertEquals(SignalAction.SELL, signal.action);
        assertTrue(signal.reason.contains("HC_SL"), "SL should fire in grace, reason=" + signal.reason);
    }

    // ============================================================
    //  Exit Flag Configuration Tests
    // ============================================================

    @Test
    public void exitFlags_emaDisabled() {
        double avgPrice = 50000;
        List<UpbitCandle> candles = buildTrendingUpCandles(80, 50000, 30);
        for (int i = 65; i < 80; i++) {
            UpbitCandle c = candles.get(i);
            double drop = (i - 65) * 100;
            c.opening_price = c.opening_price - drop;
            c.trade_price = c.opening_price - 50;
            c.high_price = c.opening_price + 20;
            c.low_price = c.trade_price - 20;
        }
        candles.get(79).trade_price = avgPrice + 100;

        PositionEntity pos = buildPosition(avgPrice, 1.0, Instant.now().minusSeconds(300));
        HighConfidenceBreakoutStrategy s = new HighConfidenceBreakoutStrategy()
                .withRisk(5.0, 0.8)
                .withExitFlags(false, false)
                .withTrailActivate(10.0)
                .withTimeStop(0, 0)
                .withTiming(23, 59);

        Signal signal = s.evaluate(exitContext(candles, pos));
        if (signal.action == SignalAction.SELL && signal.reason != null) {
            assertFalse(signal.reason.contains("HC_EMA_BREAK"), "reason=" + signal.reason);
            assertFalse(signal.reason.contains("HC_MACD_FADE"), "reason=" + signal.reason);
        }
    }

    // ============================================================
    //  Edge Case Tests
    // ============================================================

    @Test
    public void edge_nullCandles() {
        Signal signal = strategy.evaluate(new StrategyContext("KRW-TEST", 5, null, null, 0));
        assertEquals(SignalAction.NONE, signal.action);
    }

    @Test
    public void edge_nullAvgPrice() {
        List<UpbitCandle> candles = buildFlatCandles(80, 50000);
        PositionEntity pos = new PositionEntity();
        pos.setMarket("KRW-TEST");
        pos.setQty(1.0);
        pos.setAvgPrice(null);

        Signal signal = strategy.evaluate(exitContext(candles, pos));
        assertEquals(SignalAction.NONE, signal.action);
    }

    @Test
    public void edge_zeroAvgPrice() {
        List<UpbitCandle> candles = buildFlatCandles(80, 50000);
        PositionEntity pos = buildPosition(0, 1.0, Instant.now());

        Signal signal = strategy.evaluate(exitContext(candles, pos));
        assertEquals(SignalAction.NONE, signal.action);
    }

    @Test
    public void edge_dailyOpenFallback() {
        List<UpbitCandle> candles = buildCandles(80, 50000, 50, 5000);
        HighConfidenceBreakoutStrategy s = new HighConfidenceBreakoutStrategy()
                .withFilters(3.0, 0.0, 1.0);

        Signal signal = s.evaluate(entryContext(candles));
        assertNotNull(signal);
    }

    // ═══════════════════════════════════════════════════════════
    //  VOL_GATE DB 반영 검증 (2026-04-18 1안 강화)
    //  게이트 공식: volumeSurgeMult 그대로 (×0.5 제거)
    // ═══════════════════════════════════════════════════════════

    @Test
    public void volGate_respectsDbValue_dbHigh_tightensGate() {
        // DB=6.0 → gate=6.0x. vol=2200 → ratio~2.08x → BLOCKED
        List<UpbitCandle> candles = buildCandles(80, 50000, 50, 2200);
        HighConfidenceBreakoutStrategy s = new HighConfidenceBreakoutStrategy()
                .withFilters(6.0, 0.0, 1.0);

        Signal signal = s.evaluate(entryContextWithDailyOpen(candles, 49000));
        assertEquals(SignalAction.NONE, signal.action);
        assertTrue(signal.reason.contains("VOL_GATE"),
                "DB=6.0 → gate=6.0, vol~2.08x should block. reason=" + signal.reason);
        assertTrue(signal.reason.contains("6.0x"),
                "Message should show dynamic gate 6.0x. reason=" + signal.reason);
    }

    @Test
    public void volGate_respectsDbValue_dbLow_loosensGate() {
        // DB=2.0 → gate=2.0x. vol=2500 → ratio~2.33x → PASSES gate
        List<UpbitCandle> candles = buildCandles(80, 50000, 50, 2500);
        HighConfidenceBreakoutStrategy s = new HighConfidenceBreakoutStrategy()
                .withFilters(2.0, 0.0, 1.0);

        Signal signal = s.evaluate(entryContextWithDailyOpen(candles, 49000));
        if (signal.action == SignalAction.NONE) {
            assertFalse(signal.reason.contains("VOL_GATE"),
                    "DB=2.0 → gate=2.0, vol~2.33x should pass VOL_GATE. reason=" + signal.reason);
        }
    }

    @Test
    public void volGate_defaultDb3_boundaryAt3x() {
        // DB=3.0 → gate=3.0x. vol=4000 → ratio~3.48x → PASSES gate
        // (기존 1.5x 경계에서 3.0x로 강화)
        List<UpbitCandle> candles = buildCandles(80, 50000, 50, 4000);
        HighConfidenceBreakoutStrategy s = new HighConfidenceBreakoutStrategy()
                .withFilters(3.0, 0.0, 1.0);

        Signal signal = s.evaluate(entryContextWithDailyOpen(candles, 49000));
        if (signal.action == SignalAction.NONE) {
            assertFalse(signal.reason.contains("VOL_GATE"),
                    "DB=3.0 → gate=3.0, vol~3.48x should pass. reason=" + signal.reason);
        }
    }

    @Test
    public void volGate_defaultDb3_below3x_blocked() {
        // 1안 강화 검증: DB=3.0 → gate=3.0x. vol=2200 → ratio~2.08x → BLOCKED (기존엔 통과했음)
        List<UpbitCandle> candles = buildCandles(80, 50000, 50, 2200);
        HighConfidenceBreakoutStrategy s = new HighConfidenceBreakoutStrategy()
                .withFilters(3.0, 0.0, 1.0);

        Signal signal = s.evaluate(entryContextWithDailyOpen(candles, 49000));
        assertEquals(SignalAction.NONE, signal.action);
        assertTrue(signal.reason.contains("VOL_GATE"),
                "1안 강화 후 ratio=2.08x는 gate=3.0에서 차단되어야 함. reason=" + signal.reason);
    }
}
