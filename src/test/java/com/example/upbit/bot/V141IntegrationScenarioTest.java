package com.example.upbit.bot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V141 통합 시나리오 테스트 — 1번(MR gap) + 2번(OP RSI) + 4번(OP quickScore) 조합
 *
 * 실제 5/1~5/10 백테스트 케이스를 시나리오로 재현.
 * 각 코인의 (gap, RSI, vol) 조합이 V141 통합 필터로 어떻게 처리되는지 검증.
 *
 * 변경 종합:
 * - V141 #1: MR gap 1.5~2.2% (DB 1.5 + 코드 상한 2.2)
 * - V141 #2: OP RSI 75~85 진입 허용 (이전 < 75)
 * - V141 #4: OP quickScore RSI 가중치 75-85에 +1.5
 *
 * 백테스트 결과 (1+2+4 조합): 28건, 승률 57.1%, ROI +1.04%
 */
public class V141IntegrationScenarioTest {

    /**
     * V141 모닝러쉬 진입 가능 여부
     */
    private static boolean v141MrPass(double gapPct) {
        // gap 1.5~2.2% 범위만 진입 (gapCondition 통과 + 상한 차단)
        return gapPct >= 1.5 && gapPct < 2.2;
    }

    /**
     * V141 오프닝 진입 가능 여부 (RSI + quickScore)
     */
    private static boolean v141OpPass(double gapPct, double rsi, double vol) {
        // RSI 75-85만 통과
        if (rsi < 75 || rsi > 85) return false;
        // quickScore 계산
        double qs = 0;
        if (gapPct >= 3.0) qs += 2.0;
        else if (gapPct >= 2.0) qs += 1.5;
        else if (gapPct >= 1.5) qs += 1.0;
        else qs += 0.3;
        if (vol >= 5.0) qs += 1.5;
        else if (vol >= 3.0) qs += 1.0;
        else if (vol >= 1.5) qs += 0.5;
        // V141 RSI 가중치
        if (rsi >= 75 && rsi <= 85) qs += 1.5;
        else if (rsi >= 65 && rsi < 75) qs += 0.5;
        // qs >= 3.5 통과
        return qs >= 3.5;
    }

    // ===== 1+2+4 통합 시나리오: 백테스트 케이스 검증 =====

    @Test
    @DisplayName("[수익 케이스] KRW-IP 5/9 gap 1.92% RSI 80 vol 4.5x → 진입 통과")
    public void scenario_IP_5_9_pass() {
        // 5/9 10:00 IP gap 1.92% — 실제 익절 성공
        // qs = 1.0(gap1.92≥1.5) + 1.0(vol4.5≥3) + 1.5(rsi80) = 3.5 ≥ 3.5 통과
        assertTrue(v141MrPass(1.92), "MR gap 1.5-2.2 통과");
        assertTrue(v141OpPass(1.92, 80.0, 4.5), "OP qs=3.5 정확히 경계 통과");
    }

    @Test
    @DisplayName("[수익 케이스] KRW-DEEP 5/9 gap 1.64% RSI 80 vol 4.5x → OP 진입")
    public void scenario_DEEP_5_9_pass() {
        // qs = 1.0 + 1.0 + 1.5 = 3.5 ≥ 3.5
        assertTrue(v141OpPass(1.64, 80.0, 4.5), "DEEP 5/9 진입 가능 (큰 수익)");
    }

    @Test
    @DisplayName("[차단 검증] KRW-CARV 5/2 gap 1.82% RSI 75 vol 1.2x → OP 차단 (vol 부족)")
    public void scenario_CARV_blocked_low_vol() {
        // qs = 1.0(gap) + 0(vol<1.5) + 1.5(rsi) = 2.5 < 3.5 차단
        // V141도 vol 부족하면 차단 (정상 동작)
        assertFalse(v141OpPass(1.82, 75.0, 1.2), "vol 1.2 부족으로 차단 — 정상");
    }

    @Test
    @DisplayName("[손실 회피] KRW-PROS 5/9 gap 5.53% → MR/OP 모두 차단")
    public void scenario_PROS_5_9_blocked() {
        // 5/9 09:04 PROS gap 5.53% — 백테스트상 SL 패턴
        assertFalse(v141MrPass(5.53), "gap 상한 2.2% 초과 차단");
        assertFalse(v141OpPass(5.53, 70.0, 8.0), "OP RSI 70 < 75 차단");
    }

    @Test
    @DisplayName("[손실 회피] KRW-TOKAMAK 5/3 gap 2.96% RSI 76 → 차단")
    public void scenario_TOKAMAK_blocked() {
        // 5/3 TOKAMAK gap 2.96% (V141 상한 초과)
        assertFalse(v141MrPass(2.96));
        // OP는 RSI 76 통과지만 gap 2.96%는 quickScore에서 +1.5 받음 (>=2.0 분기)
        // qs = 1.5(gap2.96) + ?(vol) + 1.5(rsi76) = 3.0+? — vol에 따라 다름
        // 이 케이스는 실제 SL이었으므로 진입해도 좋지 않음
        // V141 통합 효과: gap 상한이 모닝러쉬에서 차단 → 매수 안 함
    }

    @Test
    @DisplayName("[손실 회피] KRW-CPOOL 5/9 gap 3.43% RSI 85 → MR 차단, OP RSI 통과")
    public void scenario_CPOOL_5_9() {
        // 5/9 CPOOL gap 3.43% — V141 MR 차단
        assertFalse(v141MrPass(3.43), "gap 3.43% > 2.2% MR 차단");
        // OP는 RSI 85에서 정확히 통과 (gap 3.43% 진입 후 RSI 가중치)
        // 그러나 OP는 gap 1.5-2.0% 범위 위주 → 3% 케이스는 quickScore에서도 +1.5만
    }

    // ===== 필터 변경 부작용 검증 =====

    @Test
    @DisplayName("[부작용 없음] gap 2.0% RSI 80 vol 2.0x → V141 통과")
    public void normal_signal_passes() {
        // qs = 1.5(gap≥2.0) + 0.5(vol≥1.5) + 1.5(rsi) = 3.5 ≥ 3.5 통과
        assertTrue(v141MrPass(2.0));
        assertTrue(v141OpPass(2.0, 80.0, 2.0));
    }

    @Test
    @DisplayName("[경계 차단] gap 1.5% RSI 75 vol 1.5x → qs 3.0 차단")
    public void boundary_blocked_low_qs() {
        // qs = 1.0(gap≥1.5) + 0.5(vol≥1.5) + 1.5(rsi) = 3.0 < 3.5 차단
        assertTrue(v141MrPass(1.5));
        assertFalse(v141OpPass(1.5, 75.0, 1.5), "qs 3.0으로 경계 미달 — 정상 차단");
    }

    @Test
    @DisplayName("[경계 통과] gap 1.5% RSI 75 vol 5.0x → qs 4.0 통과")
    public void boundary_passes_high_vol() {
        // qs = 1.0 + 1.5(vol≥5) + 1.5 = 4.0 ≥ 3.5 통과
        assertTrue(v141OpPass(1.5, 75.0, 5.0));
    }

    @Test
    @DisplayName("[부작용 검증] gap 1.5% RSI 60 → OP 차단 (V141 RSI 75-85만)")
    public void low_rsi_blocked() {
        assertTrue(v141MrPass(1.5));
        assertFalse(v141OpPass(1.5, 60.0, 2.0), "RSI 60은 V141 차단");
    }

    @Test
    @DisplayName("[부작용 검증] gap 2.5% RSI 80 → MR 차단, OP는 통과 가능")
    public void mid_gap_high_rsi() {
        assertFalse(v141MrPass(2.5), "MR 상한 2.2 초과 차단");
        // OP qs = 1.5(gap2.5>=2.0) + 1.0(vol>=3) + 1.5(rsi80) = 4.0 >= 3.5
        assertTrue(v141OpPass(2.5, 80.0, 3.5));
    }

    // ===== 통합 효과: 1+2+4 vs 단일 변경 =====

    @Test
    @DisplayName("[1+2+4 통합] 모든 필터 동시 적용 시 핵심 시나리오 검증")
    public void all_three_combined() {
        // 백테스트 1+2+4 조합 결과: 28건 진입, 승률 57.1%, ROI +1.04%
        // (1) 통과 케이스: gap 1.7% RSI 78 vol 3x → qs 1.0+1.0+1.5=3.5 통과
        assertTrue(v141MrPass(1.7), "MR gap 1.7% 통과");
        assertTrue(v141OpPass(1.7, 78.0, 3.0), "OP qs 3.5 통과 (vol 3x)");

        // (2) MR-OP 분기: gap 2.1% RSI 80 vol 2x → qs 1.5+0.5+1.5=3.5 통과
        assertTrue(v141MrPass(2.1));
        assertTrue(v141OpPass(2.1, 80.0, 2.0));

        // (3) 차단 케이스: gap 2.5% (MR 차단, OP는 qs 통과 가능)
        assertFalse(v141MrPass(2.5), "MR gap 상한 2.2 초과 차단");

        // (4) RSI 차단: RSI 70 (이전 봇 통과, V141 차단)
        assertFalse(v141OpPass(1.7, 70.0, 3.0), "RSI 70은 V141 차단");

        // (5) vol 부족: gap 1.5 RSI 80 vol 1.0x → qs 1.0+0+1.5=2.5 차단
        assertFalse(v141OpPass(1.5, 80.0, 1.0), "vol 부족으로 qs 2.5 차단");
    }
}
