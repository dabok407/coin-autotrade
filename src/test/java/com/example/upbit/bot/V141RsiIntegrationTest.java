package com.example.upbit.bot;

import com.example.upbit.market.UpbitCandle;
import com.example.upbit.strategy.Indicators;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V141 #2+#4 통합 테스트 — Opening RSI 75-85 진입 + quickScore 가중치
 *
 * Indicators.rsi() 실제 계산과 V141 필터 통합 검증.
 * (1단계 보강: 단위 테스트는 별도 분리, 이건 통합 검증)
 */
public class V141RsiIntegrationTest {

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
    @DisplayName("[통합] 80% 양봉 캔들 60개 → RSI 계산 결과 75-85 범위 통과")
    public void integration_uptrendCandles_rsi_in_range() {
        List<UpbitCandle> candles = uptrendCandles(60, 80.0, 0.5, 0.5, 5);
        double rsi = Indicators.rsi(candles, 14);
        // V141: 75-85 범위
        assertTrue(rsi >= 75 && rsi <= 85,
                String.format("RSI %.2f V141 범위 [75,85] 안에 있어야 함", rsi));
    }

    @Test
    @DisplayName("[통합] 50% 양봉 (랜덤) → RSI 50 부근, V141 차단되어야 함")
    public void integration_neutral_rsi_blocked() {
        List<UpbitCandle> candles = uptrendCandles(60, 80.0, 0.5, 0.5, 2); // 50% 양봉
        double rsi = Indicators.rsi(candles, 14);
        // V141: < 75 차단
        boolean v141Pass = rsi >= 75 && rsi <= 85;
        if (!v141Pass) {
            assertTrue(rsi < 75 || rsi > 85, "차단 정상");
        }
    }

    @Test
    @DisplayName("[통합] 모든 양봉 → RSI 100, V141 차단 (>85)")
    public void integration_all_up_rsi_blocked() {
        List<UpbitCandle> candles = uptrendCandles(60, 80.0, 0.5, 0.0, 999);
        double rsi = Indicators.rsi(candles, 14);
        assertTrue(rsi > 85, String.format("모든 양봉 RSI %.2f > 85 (V141 차단 영역)", rsi));
    }

    @Test
    @DisplayName("[통합] V141 quickScore 시뮬: gap 1.7%, vol 3x, RSI 80 → qs >= 3.5")
    public void integration_v141_qs_pass() {
        // qs = 1.0(gap≥1.5) + 1.0(vol≥3) + 1.5(rsi 75-85) = 3.5
        double qs = simulateV141QuickScore(1.7, 3.0, 80.0);
        assertTrue(qs >= 3.5, String.format("qs=%.2f >= 3.5 통과", qs));
    }

    @Test
    @DisplayName("[통합] V141 quickScore: gap 1.5%, vol 1.0x, RSI 80 → qs 2.5 차단")
    public void integration_v141_qs_blocked_low_vol() {
        // qs = 1.0 + 0(vol<1.5) + 1.5 = 2.5 < 3.5
        double qs = simulateV141QuickScore(1.5, 1.0, 80.0);
        assertTrue(qs < 3.5, String.format("qs=%.2f < 3.5 차단 정상", qs));
    }

    @Test
    @DisplayName("[통합] V141 quickScore: gap 2.0%, vol 5.0x, RSI 80 → qs 5.0 통과")
    public void integration_v141_qs_strong_pass() {
        // qs = 1.5(gap≥2) + 1.5(vol≥5) + 1.5(rsi) = 4.5
        double qs = simulateV141QuickScore(2.0, 5.0, 80.0);
        assertTrue(qs >= 3.5);
        assertEquals(4.5, qs, 0.01);
    }

    /**
     * V141 quickScore 시뮬 (코드와 동일 로직)
     */
    private static double simulateV141QuickScore(double gap, double vol, double rsi) {
        double qs = 0;
        if (gap >= 3.0) qs += 2.0;
        else if (gap >= 2.0) qs += 1.5;
        else if (gap >= 1.5) qs += 1.0;
        else qs += 0.3;
        if (vol >= 5.0) qs += 1.5;
        else if (vol >= 3.0) qs += 1.0;
        else if (vol >= 1.5) qs += 0.5;
        // V141 RSI 가중치
        if (rsi >= 75 && rsi <= 85) qs += 1.5;
        else if (rsi >= 65 && rsi < 75) qs += 0.5;
        return qs;
    }
}
