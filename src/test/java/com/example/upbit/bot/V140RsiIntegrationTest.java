package com.example.upbit.bot;

import com.example.upbit.market.UpbitCandle;
import com.example.upbit.strategy.Indicators;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V140 (원복) — Opening RSI < 75 진입 + quickScore 가중치 통합 테스트
 *
 * 원래 V141RsiIntegrationTest 였으나 2026-05-12 V141 원복으로 V140 검증으로 재구성.
 * Indicators.rsi() 실제 계산과 V140 필터 통합 검증.
 */
public class V140RsiIntegrationTest {

    private List<UpbitCandle> uptrendCandles(int count, double startPrice, double upPct, double downPct, int downEvery) {
        List<UpbitCandle> candles = new ArrayList<>();
        double price = startPrice;
        for (int i = 0; i < count; i++) {
            UpbitCandle c = new UpbitCandle();
            c.market = "KRW-TEST";
            boolean isDown = (i % downEvery == downEvery - 1);
            if (isDown) {
                c.opening_price = price;
                c.trade_price = price - downPct;
                price -= downPct;
            } else {
                c.opening_price = price;
                c.trade_price = price + upPct;
                price += upPct;
            }
            c.high_price = Math.max(c.opening_price, c.trade_price) + 0.05;
            c.low_price = Math.min(c.opening_price, c.trade_price) - 0.05;
            c.candle_acc_trade_volume = 1000;
            candles.add(c);
        }
        return candles;
    }

    @Test
    @DisplayName("[통합] 80% 양봉 캔들 60개 → RSI ~78, V140 차단 (>= 75)")
    public void integration_strongUptrend_blocked() {
        List<UpbitCandle> candles = uptrendCandles(60, 80.0, 0.5, 0.5, 5);
        double rsi = Indicators.rsi(candles, 14);
        // V140: rsi >= 75 차단
        assertTrue(rsi >= 75,
                String.format("80%% 양봉 RSI %.2f >= 75 (V140 차단 영역)", rsi));
    }

    @Test
    @DisplayName("[통합] 50% 양봉 (보통 추세) → RSI ~50, V140 통과")
    public void integration_neutral_pass() {
        List<UpbitCandle> candles = uptrendCandles(60, 80.0, 0.5, 0.5, 2); // 50% 양봉
        double rsi = Indicators.rsi(candles, 14);
        // V140: rsi < 75 통과
        assertTrue(rsi < 75,
                String.format("중립 RSI %.2f < 75 (V140 통과)", rsi));
    }

    @Test
    @DisplayName("[통합] 모든 양봉 → RSI 100, V140 차단 (>= 75)")
    public void integration_all_up_blocked() {
        List<UpbitCandle> candles = uptrendCandles(60, 80.0, 0.5, 0.0, 999);
        double rsi = Indicators.rsi(candles, 14);
        assertTrue(rsi >= 75, String.format("모든 양봉 RSI %.2f >= 75 (V140 차단)", rsi));
    }

    @Test
    @DisplayName("[통합] V140 quickScore 시뮬: gap 1.7%, vol 3x, RSI 60 → qs 3.0 미달 차단")
    public void integration_v140_qs_blocked_low_rsi_score() {
        // qs = 1.0(gap≥1.5) + 1.0(vol≥3) + 1.0(rsi 50-65) = 3.0 < 3.5
        double qs = simulateV140QuickScore(1.7, 3.0, 60.0);
        assertEquals(3.0, qs, 0.01);
        assertTrue(qs < 3.5, String.format("qs=%.2f < 3.5 차단", qs));
    }

    @Test
    @DisplayName("[통합] V140 quickScore: gap 2.0%, vol 3x, RSI 60 → qs 3.5 정확히 통과")
    public void integration_v140_qs_pass() {
        // qs = 1.5(gap≥2.0) + 1.0(vol≥3) + 1.0(rsi 50-65) = 3.5 ≥ 3.5 통과
        double qs = simulateV140QuickScore(2.0, 3.0, 60.0);
        assertEquals(3.5, qs, 0.01);
        assertTrue(qs >= 3.5, String.format("qs=%.2f >= 3.5 통과", qs));
    }

    @Test
    @DisplayName("[통합] V140 quickScore: gap 3.0%, vol 5.0x, RSI 70 → qs 4.1 통과")
    public void integration_v140_qs_strong_pass() {
        // qs = 2.0(gap≥3) + 1.5(vol≥5) + 0.6(rsi 65-75) = 4.1
        double qs = simulateV140QuickScore(3.0, 5.0, 70.0);
        assertTrue(qs >= 3.5);
        assertEquals(4.1, qs, 0.01);
    }

    /**
     * V140 quickScore 시뮬 (코드와 동일 로직)
     */
    private static double simulateV140QuickScore(double gap, double vol, double rsi) {
        double qs = 0;
        if (gap >= 3.0) qs += 2.0;
        else if (gap >= 2.0) qs += 1.5;
        else if (gap >= 1.5) qs += 1.0;
        else qs += 0.3;
        if (vol >= 5.0) qs += 1.5;
        else if (vol >= 3.0) qs += 1.0;
        else if (vol >= 1.5) qs += 0.5;
        // V140 RSI 가중치
        if (rsi >= 50 && rsi < 65) qs += 1.0;
        else if (rsi >= 65 && rsi < 75) qs += 0.6;
        else if (rsi < 50) qs += 0.3;
        return qs;
    }
}
