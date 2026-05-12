package com.example.upbit.bot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V140 (원복): Opening RSI 필터 + quickScore RSI 가중치 단위 테스트
 *
 * 원래 V141RsiFilterUnitTest 였으나 2026-05-12 V141 원복으로 V140 검증으로 재구성.
 *
 * V141 → V140 원복 사유:
 * - V141 (RSI [75,85] 범위) 4주 누적 ROI -58.46%, MDD 58.77%
 * - V140 단순 차단 (75 미만 통과)이 백테스트/라이브 모두 우월
 * - 3개 독립 검증 (백테스트 v3 매트릭스 + 라이브 PnL + Python 더블체크) 일치
 *
 * V140 동작:
 * - RSI 차단: rsi >= 75 → 차단 (RSI_OVERBOUGHT)
 * - quickScore Factor C:
 *     50 <= rsi < 65 → +1.0
 *     65 <= rsi < 75 → +0.6
 *     rsi < 50       → +0.3
 *     rsi >= 75      → 0 (위 필터에서 차단되므로 도달 안 함)
 */
public class V140RsiFilterUnitTest {

    /**
     * V140 RSI 필터 로직 (OpeningScannerService.java 미러)
     * 통과 = true (rsi < 75)
     */
    private static boolean rsiFilterPass(double rsi) {
        return rsi < 75;
    }

    /**
     * V140 quickScore Factor C 계산 (OpeningScannerService.java 미러)
     */
    private static double rsiQuickScore(double rsi) {
        if (rsi >= 50 && rsi < 65) return 1.0;
        if (rsi >= 65 && rsi < 75) return 0.6;
        if (rsi < 50) return 0.3;
        return 0;
    }

    // ===== RSI 필터 통과/차단 검증 =====

    @Test
    @DisplayName("RSI 30: 통과 (저 RSI)")
    public void rsi30_pass() {
        assertTrue(rsiFilterPass(30.0));
    }

    @Test
    @DisplayName("RSI 50: 통과")
    public void rsi50_pass() {
        assertTrue(rsiFilterPass(50.0));
    }

    @Test
    @DisplayName("RSI 65: 통과")
    public void rsi65_pass() {
        assertTrue(rsiFilterPass(65.0));
    }

    @Test
    @DisplayName("RSI 70: 통과 (75 미만)")
    public void rsi70_pass() {
        assertTrue(rsiFilterPass(70.0));
    }

    @Test
    @DisplayName("RSI 74.9: 통과 (75 미만)")
    public void rsi74_9_pass() {
        assertTrue(rsiFilterPass(74.9));
    }

    @Test
    @DisplayName("RSI 75: 차단 (경계값 inclusive)")
    public void rsi75_blocked() {
        assertFalse(rsiFilterPass(75.0));
    }

    @Test
    @DisplayName("RSI 76: 차단 (V141에선 통과했던 영역)")
    public void rsi76_blocked() {
        assertFalse(rsiFilterPass(76.0));
    }

    @Test
    @DisplayName("RSI 80: 차단 (V141에서 가중치 +1.5 였던 영역, V140에선 차단)")
    public void rsi80_blocked() {
        assertFalse(rsiFilterPass(80.0));
    }

    @Test
    @DisplayName("RSI 85: 차단 (V141 상한, V140에선 차단)")
    public void rsi85_blocked() {
        assertFalse(rsiFilterPass(85.0));
    }

    @Test
    @DisplayName("RSI 95: 차단 (극단 과매수)")
    public void rsi95_blocked() {
        assertFalse(rsiFilterPass(95.0));
    }

    // ===== quickScore Factor C 검증 =====

    @Test
    @DisplayName("quickScore RSI 60 → +1.0 (V140 최고 가중치)")
    public void qs_rsi60_10() {
        assertEquals(1.0, rsiQuickScore(60.0), 0.001);
    }

    @Test
    @DisplayName("quickScore RSI 50 → +1.0 (경계 inclusive)")
    public void qs_rsi50_10() {
        assertEquals(1.0, rsiQuickScore(50.0), 0.001);
    }

    @Test
    @DisplayName("quickScore RSI 64.9 → +1.0 (50-65 구간 상단)")
    public void qs_rsi64_9_10() {
        assertEquals(1.0, rsiQuickScore(64.9), 0.001);
    }

    @Test
    @DisplayName("quickScore RSI 65 → +0.6 (65-75 분기)")
    public void qs_rsi65_06() {
        assertEquals(0.6, rsiQuickScore(65.0), 0.001);
    }

    @Test
    @DisplayName("quickScore RSI 70 → +0.6 (65-75 분기)")
    public void qs_rsi70_06() {
        assertEquals(0.6, rsiQuickScore(70.0), 0.001);
    }

    @Test
    @DisplayName("quickScore RSI 40 → +0.3 (저 RSI 분기)")
    public void qs_rsi40_03() {
        assertEquals(0.3, rsiQuickScore(40.0), 0.001);
    }

    @Test
    @DisplayName("quickScore RSI 80 → 0 (필터에서 이미 차단되어 도달 X, 안전망)")
    public void qs_rsi80_zero() {
        assertEquals(0.0, rsiQuickScore(80.0), 0.001);
    }

    // ===== 시나리오 검증 =====

    @Test
    @DisplayName("시나리오: 정상 모멘텀 진입 RSI 60 → 통과 + 최고 가중치 +1.0")
    public void scenario_normalMomentum() {
        double rsi = 60.0;
        assertTrue(rsiFilterPass(rsi), "RSI 60은 V140 통과");
        assertEquals(1.0, rsiQuickScore(rsi), 0.001, "최고 가중치 부여");
    }

    @Test
    @DisplayName("시나리오: V141에서 진입했을 RSI 80 강한 모멘텀 → V140 차단 (과매수 회피)")
    public void scenario_v141Pass_v140Block() {
        double rsi = 80.0;
        assertFalse(rsiFilterPass(rsi), "V140은 RSI 80 차단 (V141 라이브 손실 회피)");
    }
}
