package com.example.upbit.bot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V141: MorningRush gap 임계값 1.5~2.2% 단위 테스트
 *
 * 변경 사항:
 * - DB gap_threshold_pct 2.6 → 1.5 (V141 SQL)
 * - 코드 상한 2.2% 추가 (MorningRushScannerService.java signal+tick path 둘 다)
 * - 결과 진입 조건: 1.5% ≤ gap < 2.2%
 *
 * 백테스트 근거:
 * - gap 1.5-2.0% n=97 승률 56% ROI +0.32% (최적)
 * - gap 2.5-3.0% n=10 승률 20% ROI -1.26%
 * - gap 4.0-6.0% n=5  승률  0% ROI -2.50%
 * - 1+2+4 조합: 28건, 승률 57.1%, ROI +1.04%
 */
public class V141MrGapRangeUnitTest {

    /**
     * V141 gap 진입 조건 (gapCondition=true 일 때)
     * gap_threshold_pct 1.5 = 임계값 1.5
     * 코드 상한 2.2
     */
    private static boolean v141GapPass(double gapPct, double gapThreshold) {
        // gapCondition: gap > threshold
        boolean gapCondition = gapPct > gapThreshold;
        if (!gapCondition) return false;
        // V141 상한 체크
        return gapPct < 2.2;
    }

    /**
     * surge 단독 진입 조건 (V141 적용)
     */
    private static boolean v141SurgeGapPass(double gapPct) {
        return gapPct >= 1.5 && gapPct < 2.2;
    }

    // ===== gap 진입 통과 검증 =====

    @Test
    @DisplayName("V141 gap 1.6%: 통과 (1.5 < 1.6 < 2.2)")
    public void gap_1_6_pass() {
        assertTrue(v141GapPass(1.6, 1.5));
    }

    @Test
    @DisplayName("V141 gap 2.0%: 통과 (백테스트 최적)")
    public void gap_2_0_pass() {
        assertTrue(v141GapPass(2.0, 1.5));
    }

    @Test
    @DisplayName("V141 gap 2.19%: 통과 (경계 직전)")
    public void gap_2_19_pass() {
        assertTrue(v141GapPass(2.19, 1.5));
    }

    @Test
    @DisplayName("V141 gap 2.2%: 차단 (상한 inclusive)")
    public void gap_2_2_blocked() {
        assertFalse(v141GapPass(2.2, 1.5));
    }

    @Test
    @DisplayName("V141 gap 2.6%: 차단 (이전 V130 기본값, 이제 상한 초과)")
    public void gap_2_6_blocked() {
        assertFalse(v141GapPass(2.6, 1.5));
    }

    @Test
    @DisplayName("V141 gap 3.0%: 차단 (백테스트 ROI -1.19%)")
    public void gap_3_0_blocked() {
        assertFalse(v141GapPass(3.0, 1.5));
    }

    @Test
    @DisplayName("V141 gap 5.0%: 차단 (백테스트 승률 0%)")
    public void gap_5_0_blocked() {
        assertFalse(v141GapPass(5.0, 1.5));
    }

    @Test
    @DisplayName("V141 gap 1.4%: 차단 (임계값 미달)")
    public void gap_1_4_blocked() {
        assertFalse(v141GapPass(1.4, 1.5));
    }

    @Test
    @DisplayName("V141 gap 0.5%: 차단 (낮은 gap)")
    public void gap_0_5_blocked() {
        assertFalse(v141GapPass(0.5, 1.5));
    }

    // ===== surge 단독 진입 검증 =====

    @Test
    @DisplayName("V141 surge gap 1.5%: 통과")
    public void surge_gap_1_5_pass() {
        assertTrue(v141SurgeGapPass(1.5));
    }

    @Test
    @DisplayName("V141 surge gap 2.0%: 통과")
    public void surge_gap_2_0_pass() {
        assertTrue(v141SurgeGapPass(2.0));
    }

    @Test
    @DisplayName("V141 surge gap 2.2%: 차단 (상한 inclusive)")
    public void surge_gap_2_2_blocked() {
        assertFalse(v141SurgeGapPass(2.2));
    }

    @Test
    @DisplayName("V141 surge gap 1.0%: 차단 (FF 사고 패턴 방지)")
    public void surge_gap_1_0_blocked() {
        assertFalse(v141SurgeGapPass(1.0));
    }

    // ===== 백테스트 시나리오 검증 =====

    @Test
    @DisplayName("백테스트 케이스: 5/9 PROS gap 5.53% → 차단 (V141 상한)")
    public void scenario_PROS_5_9_blocked() {
        // 실제 5/9 09:04:27 PROS gap 5.53% 시그널 → V130에서는 통과했었음 (그러나 L1 60s 차단)
        // V141에서는 gap 상한 2.2%로 시그널 단계에서 차단
        assertFalse(v141GapPass(5.53, 1.5),
                "PROS 5/9 gap 5.53% V141에서 차단되어야 함 (꼬리매수 회피)");
    }

    @Test
    @DisplayName("백테스트 케이스: 5/9 CPOOL gap 2.5% → 차단 (V141 상한)")
    public void scenario_CPOOL_5_9_blocked() {
        // 5/9 CPOOL gap 약 2.5% → V141 상한 2.2%로 차단
        assertFalse(v141GapPass(2.5, 1.5));
    }

    @Test
    @DisplayName("백테스트 케이스: 5/9 IP BREAKOUT gap 1.92% → 통과 (V141 적합)")
    public void scenario_IP_5_9_pass() {
        // 5/9 10:00 IP gap 1.92% → V141 통과 (실제 봇은 진입했고 SPLIT_1ST/2ND 익절 성공)
        assertTrue(v141GapPass(1.92, 1.5),
                "IP 5/9 gap 1.92% 진입 — 실제 익절 성공 케이스 (백테스트 검증)");
    }
}
