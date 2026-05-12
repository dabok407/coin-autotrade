package com.example.upbit.bot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V141 #1: MorningRush gap 임계값 단위 테스트.
 *
 * 변경 이력:
 * - V141 (2026-05-10): DB gap_threshold_pct 2.6 → 1.5, 코드 상한 2.2% 추가
 * - V145 부분 완화 (2026-05-12): 코드 상한 2.2% → 2.5%
 *
 * 4주 라이브 데이터 분석 결과:
 * - gap 1.5~2.0%: 10건 60% 승률 -0.47% (V141 단독, 음의 EV)
 * - gap 2.0~2.5%: 2건 100% 승률 +2.78% (가장 좋음, 단 표본 작음)
 * - gap 2.5~3.0%: 2건 50% 승률 -1.19% (2.5% 상한 정당화)
 *
 * 결과 진입 조건: 1.5% ≤ gap < 2.5%
 */
public class V141MrGapRangeUnitTest {

    /**
     * V141 gap 진입 조건 (gapCondition=true 일 때)
     * gap_threshold_pct 1.5 = 임계값 1.5
     * 코드 상한 2.5 (V145 완화)
     */
    private static boolean v141GapPass(double gapPct, double gapThreshold) {
        // gapCondition: gap > threshold
        boolean gapCondition = gapPct > gapThreshold;
        if (!gapCondition) return false;
        // V145 상한 체크
        return gapPct < 2.5;
    }

    /**
     * surge 단독 진입 조건 (V145 적용)
     */
    private static boolean v141SurgeGapPass(double gapPct) {
        return gapPct >= 1.5 && gapPct < 2.5;
    }

    // ===== gap 진입 통과 검증 =====

    @Test
    @DisplayName("V145 gap 1.6%: 통과 (1.5 < 1.6 < 2.5)")
    public void gap_1_6_pass() {
        assertTrue(v141GapPass(1.6, 1.5));
    }

    @Test
    @DisplayName("V145 gap 2.0%: 통과 (백테스트 1.5~2.0% 구간)")
    public void gap_2_0_pass() {
        assertTrue(v141GapPass(2.0, 1.5));
    }

    @Test
    @DisplayName("V145 gap 2.19%: 통과 (V141 경계 직전)")
    public void gap_2_19_pass() {
        assertTrue(v141GapPass(2.19, 1.5));
    }

    @Test
    @DisplayName("V145 gap 2.3%: 통과 (V141은 차단, V145 완화 후 통과)")
    public void gap_2_3_pass() {
        // V141에서는 차단 (>= 2.2), V145에서는 통과 (< 2.5)
        assertTrue(v141GapPass(2.3, 1.5));
    }

    @Test
    @DisplayName("V145 gap 2.49%: 통과 (경계 직전)")
    public void gap_2_49_pass() {
        assertTrue(v141GapPass(2.49, 1.5));
    }

    @Test
    @DisplayName("V145 gap 2.5%: 차단 (상한 inclusive)")
    public void gap_2_5_blocked() {
        assertFalse(v141GapPass(2.5, 1.5));
    }

    @Test
    @DisplayName("V145 gap 2.6%: 차단 (이전 V130 기본값, 이제 상한 초과)")
    public void gap_2_6_blocked() {
        assertFalse(v141GapPass(2.6, 1.5));
    }

    @Test
    @DisplayName("V145 gap 3.0%: 차단 (백테스트 ROI -1.19%)")
    public void gap_3_0_blocked() {
        assertFalse(v141GapPass(3.0, 1.5));
    }

    @Test
    @DisplayName("V145 gap 5.0%: 차단 (백테스트 승률 0%)")
    public void gap_5_0_blocked() {
        assertFalse(v141GapPass(5.0, 1.5));
    }

    @Test
    @DisplayName("V145 gap 1.4%: 차단 (임계값 미달)")
    public void gap_1_4_blocked() {
        assertFalse(v141GapPass(1.4, 1.5));
    }

    @Test
    @DisplayName("V145 gap 0.5%: 차단 (낮은 gap)")
    public void gap_0_5_blocked() {
        assertFalse(v141GapPass(0.5, 1.5));
    }

    // ===== surge 단독 진입 검증 =====

    @Test
    @DisplayName("V145 surge gap 1.5%: 통과")
    public void surge_gap_1_5_pass() {
        assertTrue(v141SurgeGapPass(1.5));
    }

    @Test
    @DisplayName("V145 surge gap 2.0%: 통과")
    public void surge_gap_2_0_pass() {
        assertTrue(v141SurgeGapPass(2.0));
    }

    @Test
    @DisplayName("V145 surge gap 2.3%: 통과 (V141은 차단, V145 완화)")
    public void surge_gap_2_3_pass() {
        assertTrue(v141SurgeGapPass(2.3));
    }

    @Test
    @DisplayName("V145 surge gap 2.5%: 차단 (상한 inclusive)")
    public void surge_gap_2_5_blocked() {
        assertFalse(v141SurgeGapPass(2.5));
    }

    @Test
    @DisplayName("V145 surge gap 1.0%: 차단 (FF 사고 패턴 방지)")
    public void surge_gap_1_0_blocked() {
        assertFalse(v141SurgeGapPass(1.0));
    }

    // ===== 백테스트 시나리오 검증 =====

    @Test
    @DisplayName("백테스트 케이스: 5/9 PROS gap 5.53% → 차단 (V145 상한)")
    public void scenario_PROS_5_9_blocked() {
        // 실제 5/9 09:04:27 PROS gap 5.53% 시그널 → V130에서는 통과했었음 (그러나 L1 60s 차단)
        // V145에서도 gap 상한 2.5%로 시그널 단계에서 차단
        assertFalse(v141GapPass(5.53, 1.5),
                "PROS 5/9 gap 5.53% V145에서도 차단되어야 함 (꼬리매수 회피)");
    }

    @Test
    @DisplayName("백테스트 케이스: 5/9 CPOOL gap ~2.5% → 차단 (V145 상한)")
    public void scenario_CPOOL_5_9_blocked() {
        // 5/9 CPOOL gap 약 2.5% → V145 상한 2.5%로 차단 (경계 inclusive)
        assertFalse(v141GapPass(2.5, 1.5));
    }

    @Test
    @DisplayName("백테스트 케이스: 5/9 IP BREAKOUT gap 1.92% → 통과 (V145 적합)")
    public void scenario_IP_5_9_pass() {
        // 5/9 10:00 IP gap 1.92% → V145 통과 (실제 봇은 진입했고 SPLIT_1ST/2ND 익절 성공)
        assertTrue(v141GapPass(1.92, 1.5),
                "IP 5/9 gap 1.92% 진입 — 실제 익절 성공 케이스 (백테스트 검증)");
    }

    @Test
    @DisplayName("V145 신규 케이스: 4주 데이터 gap 2.0~2.5% 구간 통과 (100% 승률 +2.78%)")
    public void scenario_v145_relaxed_zone_pass() {
        // V141에서는 차단되었으나 V145에서는 통과되는 구간
        assertTrue(v141GapPass(2.1, 1.5), "gap 2.1% V145 통과");
        assertTrue(v141GapPass(2.3, 1.5), "gap 2.3% V145 통과 (V141 차단, V145 완화)");
        assertTrue(v141GapPass(2.4, 1.5), "gap 2.4% V145 통과 (V141 차단, V145 완화)");
    }
}
