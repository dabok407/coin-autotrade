package com.example.upbit.bot;

import com.example.upbit.market.UpbitCandle;
import com.example.upbit.strategy.Indicators;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 오프닝 스캐너 옵션 B (`tryWsBreakoutBuy`) 1분봉 필터 검사 시나리오 테스트.
 *
 * 검증 항목 (4가지 필터):
 *  1. 거래량 ≥ 평균 × 1.5
 *  2. RSI(14) < 83
 *  3. EMA(20) 위 (price > ema20)
 *  4. 돌파 강도 ≥ 1.0%
 */
public class OpeningOptionBFilterScenarioTest {

    public enum FilterResult {
        PASS,
        VOLUME_LOW,
        RSI_OVERBOUGHT,
        BELOW_EMA20,
        BREAKOUT_WEAK,
        INSUFFICIENT_CANDLES
    }

    /** 옵션 B 필터 검사 (운영 코드 동일 로직) */
    private FilterResult evaluateOptionBFilters(List<UpbitCandle> candles, double wsPrice, double breakoutPctActual) {
        if (candles == null || candles.size() < 25) return FilterResult.INSUFFICIENT_CANDLES;

        UpbitCandle last = candles.get(candles.size() - 1);

        // 1. 거래량 1.5배
        double avgVol = 0;
        int volCount = Math.min(20, candles.size());
        for (int i = candles.size() - volCount; i < candles.size(); i++) {
            avgVol += candles.get(i).candle_acc_trade_volume;
        }
        avgVol /= volCount;
        double curVol = last.candle_acc_trade_volume;
        double volRatio = avgVol > 0 ? curVol / avgVol : 0;
        if (volRatio < 1.5) return FilterResult.VOLUME_LOW;

        // 3. RSI < 83
        double rsi = Indicators.rsi(candles, 14);
        if (rsi >= 83) return FilterResult.RSI_OVERBOUGHT;

        // 4. EMA20 위
        double ema20 = Indicators.ema(candles, 20);
        if (!Double.isNaN(ema20) && wsPrice < ema20) return FilterResult.BELOW_EMA20;

        // 5. 돌파 강도
        if (breakoutPctActual < 1.0) return FilterResult.BREAKOUT_WEAK;

        return FilterResult.PASS;
    }

    /**
     * 횡보 캔들 (RSI ~50, EMA20 ~100): 가격이 99~101 사이 흔들림
     * 마지막 캔들의 OHLC와 volume은 별도 지정 가능.
     */
    private List<UpbitCandle> sidewaysCandles(boolean lastBullish, double lastVolMult) {
        List<UpbitCandle> candles = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            UpbitCandle c = new UpbitCandle();
            c.market = "KRW-TEST";
            c.candle_date_time_utc = String.format("2026-04-08T0%d:0%d:00", i / 10, i % 10);

            // 가격 횡보: 100 ± 0.5
            double base = 100.0 + (i % 4 == 0 ? 0.5 : (i % 4 == 1 ? -0.3 : (i % 4 == 2 ? 0.3 : -0.5)));
            c.opening_price = base;
            c.trade_price = base + (i % 2 == 0 ? 0.1 : -0.1); // 양봉/음봉 교대
            c.high_price = Math.max(c.opening_price, c.trade_price) + 0.1;
            c.low_price = Math.min(c.opening_price, c.trade_price) - 0.1;
            c.candle_acc_trade_volume = 1000;
            candles.add(c);
        }

        // 마지막 캔들 직접 지정
        UpbitCandle last = candles.get(59);
        if (lastBullish) {
            last.opening_price = 100.0;
            last.trade_price = 100.5;  // 양봉
            last.high_price = 100.6;
            last.low_price = 99.9;
        } else {
            last.opening_price = 100.5;
            last.trade_price = 100.0;  // 음봉
            last.high_price = 100.6;
            last.low_price = 99.9;
        }
        last.candle_acc_trade_volume = (long) (1000 * lastVolMult);
        return candles;
    }

    /** 강한 상승 캔들 (RSI 90+) */
    private List<UpbitCandle> strongUptrendCandles(double lastVolMult) {
        List<UpbitCandle> candles = new ArrayList<>();
        // 모든 캔들 양봉, 가격 단조 증가 → RSI 100
        for (int i = 0; i < 60; i++) {
            UpbitCandle c = new UpbitCandle();
            c.market = "KRW-TEST";
            c.candle_date_time_utc = String.format("2026-04-08T0%d:0%d:00", i / 10, i % 10);
            double price = 100.0 + i * 1.0;
            c.opening_price = price;
            c.trade_price = price + 0.5;
            c.high_price = c.trade_price + 0.1;
            c.low_price = c.opening_price - 0.1;
            c.candle_acc_trade_volume = 1000;
            candles.add(c);
        }
        candles.get(59).candle_acc_trade_volume = (long) (1000 * lastVolMult);
        return candles;
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 1: 모든 필터 통과 → PASS
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("시나리오 1: 횡보 + 양봉 + vol 2x + RSI ~50 + 돌파 1.5% → PASS")
    public void scenario1_allPass() {
        List<UpbitCandle> candles = sidewaysCandles(true, 2.0);
        // EMA20 ~100, wsPrice 100.5 (위)
        FilterResult result = evaluateOptionBFilters(candles, 100.5, 1.5);
        assertEquals(FilterResult.PASS, result, "RSI=" + Indicators.rsi(candles, 14));
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 2 (과거 BEARISH_LAST_1MIN): 음봉이어도 진입 허용 (필터 제거됨)
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("시나리오 2: 직전 1분봉 음봉이어도 다른 필터 통과면 PASS")
    public void scenario2_bearishNoLongerBlocks() {
        List<UpbitCandle> candles = sidewaysCandles(false, 2.0);
        FilterResult result = evaluateOptionBFilters(candles, 100.5, 1.5);
        assertEquals(FilterResult.PASS, result,
                "음봉 필터 제거: 음봉이어도 vol/RSI/EMA/돌파 통과면 진입 허용");
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 3: 거래량 1.5배 미달 → VOLUME_LOW
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("시나리오 3: 거래량 1.0x → VOLUME_LOW")
    public void scenario3_volumeLow() {
        List<UpbitCandle> candles = sidewaysCandles(true, 1.0);
        FilterResult result = evaluateOptionBFilters(candles, 100.5, 1.5);
        assertEquals(FilterResult.VOLUME_LOW, result);
    }

    @Test
    @DisplayName("시나리오 3-1: 거래량 1.49x (경계값 미달) → VOLUME_LOW")
    public void scenario3_1_volumeLowBoundary() {
        List<UpbitCandle> candles = sidewaysCandles(true, 1.49);
        FilterResult result = evaluateOptionBFilters(candles, 100.5, 1.5);
        assertEquals(FilterResult.VOLUME_LOW, result);
    }

    @Test
    @DisplayName("시나리오 3-2: 거래량 1.6x (경계값 위) → VOLUME_LOW 미발동")
    public void scenario3_2_volumeBoundaryPass() {
        // 마지막 캔들 vol이 평균 계산에 포함되어 1.5x 정확값은 미달.
        // 1.6x로 설정 (vol 1600 vs avg 1030 = 1.55x → 통과)
        List<UpbitCandle> candles = sidewaysCandles(true, 1.6);
        FilterResult result = evaluateOptionBFilters(candles, 100.5, 1.5);
        assertNotEquals(FilterResult.VOLUME_LOW, result);
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 4: RSI 과매수 → RSI_OVERBOUGHT
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("시나리오 4: 강한 상승 캔들 (RSI 100) → RSI_OVERBOUGHT")
    public void scenario4_rsiOverbought() {
        List<UpbitCandle> candles = strongUptrendCandles(2.0);
        // EMA20 ~150, wsPrice 200 (위)
        FilterResult result = evaluateOptionBFilters(candles, 200.0, 1.5);
        assertEquals(FilterResult.RSI_OVERBOUGHT, result,
                "RSI=" + Indicators.rsi(candles, 14));
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 5: EMA20 아래 가격 → BELOW_EMA20
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("시나리오 5: WS 가격이 EMA20 한참 아래 → BELOW_EMA20")
    public void scenario5_belowEma20() {
        List<UpbitCandle> candles = sidewaysCandles(true, 2.0);
        // EMA20 ~100, wsPrice 90 (한참 아래)
        FilterResult result = evaluateOptionBFilters(candles, 90.0, 1.5);
        assertEquals(FilterResult.BELOW_EMA20, result);
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 6: 돌파 강도 미달 → BREAKOUT_WEAK
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("시나리오 6: 돌파 강도 0.8% (1.0% 미달) → BREAKOUT_WEAK")
    public void scenario6_breakoutWeak() {
        List<UpbitCandle> candles = sidewaysCandles(true, 2.0);
        FilterResult result = evaluateOptionBFilters(candles, 100.5, 0.8);
        assertEquals(FilterResult.BREAKOUT_WEAK, result);
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 7: 캔들 부족 → INSUFFICIENT_CANDLES
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("시나리오 7: 캔들 20개 (25개 미달) → INSUFFICIENT_CANDLES")
    public void scenario7_insufficientCandles() {
        List<UpbitCandle> candles = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            UpbitCandle c = new UpbitCandle();
            c.opening_price = 100;
            c.trade_price = 101;
            c.high_price = 102;
            c.low_price = 99;
            c.candle_acc_trade_volume = 1000;
            candles.add(c);
        }
        FilterResult result = evaluateOptionBFilters(candles, 110.0, 1.5);
        assertEquals(FilterResult.INSUFFICIENT_CANDLES, result);
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 8: null 캔들 → INSUFFICIENT_CANDLES
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("시나리오 8: 캔들 null → INSUFFICIENT_CANDLES")
    public void scenario8_nullCandles() {
        FilterResult result = evaluateOptionBFilters(null, 110.0, 1.5);
        assertEquals(FilterResult.INSUFFICIENT_CANDLES, result);
    }
}
