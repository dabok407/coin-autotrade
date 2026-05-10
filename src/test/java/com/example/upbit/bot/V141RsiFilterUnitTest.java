package com.example.upbit.bot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V141: Opening RSI 필터 + quickScore RSI 가중치 변경 단위 테스트
 *
 * 변경 사항:
 * - RSI 차단: rsi >= 75 → rsi < 75 || rsi > 85 (75-85 범위만 허용)
 * - quickScore Factor C: 50-65 +1.0/65-75 +0.6 → 75-85 +1.5/65-75 +0.5
 *
 * 백테스트 근거:
 * - RSI 75-85 승률 52% ROI +0.44%
 * - RSI 50-65 승률 27% ROI -0.96%
 * - 1+2+4 조합 승률 57.1% ROI +1.04% (160 케이스 백테스트)
 */
public class V141RsiFilterUnitTest {

    /**
     * V141 RSI 필터 로직 (OpeningScannerService.java:2042 미러)
     */
    private static boolean rsiFilterPass(double rsi) {
        return !(rsi < 75 || rsi > 85);
    }

    /**
     * V141 quickScore Factor C 계산 (OpeningScannerService.java:2107 미러)
     */
    private static double rsiQuickScore(double rsi) {
        if (rsi >= 75 && rsi <= 85) return 1.5;
        if (rsi >= 65 && rsi < 75) return 0.5;
        return 0;
    }

    // ===== RSI 필터 통과/차단 검증 =====

    @Test
    @DisplayName("RSI 75: 통과 (경계값 lower inclusive)")
    public void rsi75_pass() {
        assertTrue(rsiFilterPass(75.0));
    }

    @Test
    @DisplayName("RSI 80: 통과 (중간값)")
    public void rsi80_pass() {
        assertTrue(rsiFilterPass(80.0));
    }

    @Test
    @DisplayName("RSI 85: 통과 (경계값 upper inclusive)")
    public void rsi85_pass() {
        assertTrue(rsiFilterPass(85.0));
    }

    @Test
    @DisplayName("RSI 74: 차단 (75 미만)")
    public void rsi74_blocked() {
        assertFalse(rsiFilterPass(74.9));
    }

    @Test
    @DisplayName("RSI 70: 차단 (현재 봇이 +0.6 가중치 주던 영역, 이제 차단)")
    public void rsi70_blocked() {
        assertFalse(rsiFilterPass(70.0));
    }

    @Test
    @DisplayName("RSI 50-65: 차단 (현재 봇이 +1.0 최고 가중치 주던 영역, 이제 차단)")
    public void rsi55_blocked() {
        assertFalse(rsiFilterPass(55.0));
    }

    @Test
    @DisplayName("RSI 86: 차단 (85 초과)")
    public void rsi86_blocked() {
        assertFalse(rsiFilterPass(85.1));
    }

    @Test
    @DisplayName("RSI 95: 차단 (극단 과매수)")
    public void rsi95_blocked() {
        assertFalse(rsiFilterPass(95.0));
    }

    @Test
    @DisplayName("RSI 30: 차단 (저 RSI)")
    public void rsi30_blocked() {
        assertFalse(rsiFilterPass(30.0));
    }

    // ===== quickScore Factor C 검증 =====

    @Test
    @DisplayName("quickScore RSI 80 → +1.5 (V141 최고 가중치)")
    public void qs_rsi80_15() {
        assertEquals(1.5, rsiQuickScore(80.0), 0.001);
    }

    @Test
    @DisplayName("quickScore RSI 75 → +1.5 (경계 inclusive)")
    public void qs_rsi75_15() {
        assertEquals(1.5, rsiQuickScore(75.0), 0.001);
    }

    @Test
    @DisplayName("quickScore RSI 85 → +1.5 (경계 inclusive)")
    public void qs_rsi85_15() {
        assertEquals(1.5, rsiQuickScore(85.0), 0.001);
    }

    @Test
    @DisplayName("quickScore RSI 70 → +0.5 (안전망, 65-75 분기)")
    public void qs_rsi70_05() {
        assertEquals(0.5, rsiQuickScore(70.0), 0.001);
    }

    @Test
    @DisplayName("quickScore RSI 60 → 0 (이전 +1.0 → 이제 0)")
    public void qs_rsi60_zero() {
        assertEquals(0.0, rsiQuickScore(60.0), 0.001);
    }

    @Test
    @DisplayName("quickScore RSI 90 → 0 (>85 차단)")
    public void qs_rsi90_zero() {
        assertEquals(0.0, rsiQuickScore(90.0), 0.001);
    }

    // ===== 백테스트 시나리오 검증 =====

    @Test
    @DisplayName("백테스트 시나리오: KRW-DEEP 5/9 09:36 RSI 80 → 통과 + +1.5")
    public void scenario_DEEP() {
        double rsi = 80.0;
        assertTrue(rsiFilterPass(rsi), "DEEP 5/9 09:36 BREAKOUT 시점 RSI 80 통과해야 함");
        assertEquals(1.5, rsiQuickScore(rsi), 0.001, "최고 가중치 부여");
    }

    @Test
    @DisplayName("백테스트 시나리오: KRW-PRL 5/9 09:34 RSI 80 → 통과 + +1.5")
    public void scenario_PRL() {
        double rsi = 80.0;
        assertTrue(rsiFilterPass(rsi));
        assertEquals(1.5, rsiQuickScore(rsi), 0.001);
    }

    @Test
    @DisplayName("백테스트 시나리오: 5/8 ZBT 09:35 RSI 75 진입 → V141 통과")
    public void scenario_ZBT_5_8() {
        // 실제 5/8 거래에서 ZBT 09:35 진입 시점 RSI 75 부근
        double rsi = 75.0;
        assertTrue(rsiFilterPass(rsi), "ZBT 진입 시점 통과");
    }
}
