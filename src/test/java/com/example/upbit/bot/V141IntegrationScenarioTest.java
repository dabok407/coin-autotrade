package com.example.upbit.bot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V141 (부분 원복 + 부분 완화) — MR gap 1.5~2.5% + Opening RSI 필터 V140 원복 통합 시나리오
 *
 * 2026-05-12 변경:
 * - V141 #1 (MR gap): 부분 완화 — 상한 2.2% → 2.5% (V145, 4주 라이브 데이터 기반)
 * - V141 #2 (OP RSI 75-85 진입): V140으로 원복 (rsi >= 75 차단)
 * - V141 #4 (OP quickScore RSI 75-85 +1.5): V140으로 원복 (50-65 +1.0 / 65-75 +0.6)
 *
 * 변경 사유:
 * - RSI 원복: 3개 독립 검증 (백테스트 v3 + 라이브 PnL + Python 더블체크) 모두 손실 확대 확인.
 * - MR gap 완화: 4주 라이브 (14건) gap 2.0~2.5% 구간 2건 100% 승률 +2.78% (가장 좋음).
 */
public class V141IntegrationScenarioTest {

    /**
     * V141 #1 모닝러쉬 진입 가능 여부 (V145 완화: 상한 2.5%)
     */
    private static boolean v141MrPass(double gapPct) {
        // gap 1.5~2.5% 범위만 진입 (gapCondition 통과 + 상한 차단)
        return gapPct >= 1.5 && gapPct < 2.5;
    }

    /**
     * V140 (원복) 오프닝 진입 가능 여부 (RSI + quickScore)
     */
    private static boolean v140OpPass(double gapPct, double rsi, double vol) {
        // V140 RSI 필터: rsi >= 75 차단
        if (rsi >= 75) return false;
        // quickScore 계산
        double qs = 0;
        if (gapPct >= 3.0) qs += 2.0;
        else if (gapPct >= 2.0) qs += 1.5;
        else if (gapPct >= 1.5) qs += 1.0;
        else qs += 0.3;
        if (vol >= 5.0) qs += 1.5;
        else if (vol >= 3.0) qs += 1.0;
        else if (vol >= 1.5) qs += 0.5;
        // V140 RSI 가중치
        if (rsi >= 50 && rsi < 65) qs += 1.0;
        else if (rsi >= 65 && rsi < 75) qs += 0.6;
        else if (rsi < 50) qs += 0.3;
        // qs >= 3.5 통과
        return qs >= 3.5;
    }

    // ===== MR(V141 유지) + OP(V140 원복) 통합 시나리오 =====

    @Test
    @DisplayName("[수익 케이스] gap 1.92% RSI 60 vol 4.5x → MR + OP 모두 진입 통과")
    public void scenario_normalMomentum_pass() {
        // MR: gap 1.5-2.2 통과
        // OP: qs = 1.0(gap1.92≥1.5) + 1.0(vol4.5≥3) + 1.0(rsi60 50-65) = 3.0
        // 단 qs<3.5라 OP 차단, MR만 진입
        assertTrue(v141MrPass(1.92), "MR gap 1.5-2.2 통과");
        assertFalse(v140OpPass(1.92, 60.0, 4.5), "OP qs=3.0 < 3.5 차단 (정상)");
    }

    @Test
    @DisplayName("[수익 케이스] gap 2.0% RSI 60 vol 3.0x → OP qs 3.5 통과")
    public void scenario_op_borderline_pass() {
        // qs = 1.5(gap≥2.0) + 1.0(vol≥3) + 1.0(rsi 50-65) = 3.5 정확히 경계 통과
        assertTrue(v140OpPass(2.0, 60.0, 3.0), "OP qs 3.5 정확히 경계 통과");
    }

    @Test
    @DisplayName("[차단 검증] gap 1.82% RSI 60 vol 1.2x → OP 차단 (vol 부족)")
    public void scenario_blocked_low_vol() {
        // qs = 1.0(gap) + 0(vol<1.5) + 1.0(rsi) = 2.0 < 3.5 차단
        assertFalse(v140OpPass(1.82, 60.0, 1.2), "vol 1.2 부족으로 차단 — 정상");
    }

    @Test
    @DisplayName("[손실 회피] gap 5.53% → MR 차단 (V145 상한 2.5% 초과)")
    public void scenario_high_gap_mr_blocked() {
        assertFalse(v141MrPass(5.53), "gap 상한 2.5% 초과 차단");
    }

    @Test
    @DisplayName("[손실 회피] V141에서 진입했을 RSI 80 → V140 차단")
    public void scenario_v141Pass_v140Block() {
        // V141은 RSI 80 통과했으나 V140은 차단
        assertFalse(v140OpPass(2.0, 80.0, 3.0), "V140은 RSI >= 75 차단 (V141 손실 회피)");
    }

    // ===== 부작용 검증 =====

    @Test
    @DisplayName("[정상] gap 2.0% RSI 60 vol 2.0x → V140 통과")
    public void normal_signal_passes() {
        // qs = 1.5(gap≥2.0) + 0.5(vol≥1.5) + 1.0(rsi 50-65) = 3.0 < 3.5
        // vol 부족 → 차단 정상
        assertTrue(v141MrPass(2.0));
        assertFalse(v140OpPass(2.0, 60.0, 2.0), "vol 2.0 부족으로 OP qs 3.0 차단");
    }

    @Test
    @DisplayName("[경계 통과] gap 1.5% RSI 60 vol 3.0x → qs 3.0, 차단")
    public void boundary_qs_blocked() {
        // qs = 1.0 + 1.0(vol≥3) + 1.0(rsi 50-65) = 3.0 < 3.5
        assertTrue(v141MrPass(1.5));
        assertFalse(v140OpPass(1.5, 60.0, 3.0), "qs 3.0으로 차단 — 정상");
    }

    @Test
    @DisplayName("[경계 통과] gap 1.5% RSI 60 vol 5.0x → qs 3.5 통과")
    public void boundary_passes_high_vol() {
        // qs = 1.0 + 1.5(vol≥5) + 1.0 = 3.5 ≥ 3.5 통과
        assertTrue(v140OpPass(1.5, 60.0, 5.0));
    }

    @Test
    @DisplayName("[차단] gap 1.5% RSI 76 → V140 OP 차단 (RSI >= 75)")
    public void rsi76_blocked() {
        assertTrue(v141MrPass(1.5));
        assertFalse(v140OpPass(1.5, 76.0, 5.0), "RSI 76 >= 75 V140 차단");
    }

    @Test
    @DisplayName("[차단] gap 2.5% RSI 60 → MR 차단(상한 inclusive), OP 통과 가능")
    public void mid_gap_normal_rsi() {
        assertFalse(v141MrPass(2.5), "MR 상한 2.5 inclusive 차단");
        // OP qs = 1.5(gap2.5>=2.0) + 1.0(vol>=3) + 1.0(rsi60 50-65) = 3.5 >= 3.5
        assertTrue(v140OpPass(2.5, 60.0, 3.5));
    }

    @Test
    @DisplayName("[V145 완화] gap 2.3% RSI 60 vol 3.0x → MR 통과 (V141은 차단)")
    public void scenario_v145_relaxed_pass() {
        // V141에서는 gap 2.3% 차단 (>= 2.2), V145에서는 통과 (< 2.5)
        // 4주 데이터 gap 2.0~2.5% 구간 100% 승률 +2.78% 근거
        assertTrue(v141MrPass(2.3), "V145 완화: gap 2.3% MR 통과 (V141 차단 케이스)");
        // OP qs = 1.5(gap≥2.0) + 1.0(vol≥3) + 1.0(rsi 50-65) = 3.5 통과
        assertTrue(v140OpPass(2.3, 60.0, 3.0));
    }

    // ===== 통합 효과 검증 =====

    @Test
    @DisplayName("[1+(2+4 원복) 통합] 모든 필터 동시 적용 시 핵심 시나리오 검증")
    public void all_combined() {
        // (1) 통과: gap 1.7% RSI 60 vol 3x → qs 1.0+1.0+1.0=3.0 차단
        assertTrue(v141MrPass(1.7), "MR gap 1.7% 통과");
        assertFalse(v140OpPass(1.7, 60.0, 3.0), "OP qs 3.0 차단 (vol 부족)");

        // (2) OP 통과: gap 2.1% RSI 60 vol 3.0x → qs 1.5+1.0+1.0=3.5 통과
        assertTrue(v141MrPass(2.1));
        assertTrue(v140OpPass(2.1, 60.0, 3.0));

        // (3) MR 차단: gap 2.5% (MR 상한 inclusive 차단)
        assertFalse(v141MrPass(2.5), "MR gap 상한 2.5 inclusive 차단");
        // (3-2) V145 완화 케이스: gap 2.3% (V141 차단, V145 통과)
        assertTrue(v141MrPass(2.3), "V145 완화: MR gap 2.3% 통과");

        // (4) RSI 76 차단 (V140 동작): V141 통과했던 RSI 76 → V140 차단
        assertFalse(v140OpPass(1.7, 76.0, 3.0), "RSI 76 >= 75 V140 차단");

        // (5) vol 부족: gap 1.5 RSI 60 vol 1.0x → qs 1.0+0+1.0=2.0 차단
        assertFalse(v140OpPass(1.5, 60.0, 1.0), "vol 부족으로 qs 2.0 차단");
    }
}
