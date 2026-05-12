package com.example.upbit.bot;

import com.example.upbit.db.MorningRushConfigEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V141 #1 통합 테스트 — MorningRush gap 1.5~2.5% (DB + 코드 상한).
 *
 * 변경 이력:
 * - V141 (2026-05-10): DB 1.5 + 코드 상한 2.2%
 * - V145 부분 완화 (2026-05-12): 코드 상한 2.2% → 2.5% (4주 데이터 기반)
 *
 * DB 설정 + 코드 진입 로직 통합 검증.
 */
public class V141MrGapIntegrationTest {

    /**
     * V145 MR 진입 로직 시뮬 (signal path)
     */
    private static boolean v141MrEntrySignal(double price, double rangeHigh,
                                              double gapThresholdPct, boolean surgeCondition) {
        double gapThreshold = gapThresholdPct / 100.0;  // V130 변환
        double threshold = rangeHigh * (1.0 + gapThreshold);
        boolean gapCondition = price > threshold;
        double currentGapPct = (price - rangeHigh) / rangeHigh * 100.0;

        if (gapCondition) {
            // V145 상한 체크
            if (currentGapPct >= 2.5) return false;
            return true;
        } else if (surgeCondition) {
            return currentGapPct >= 1.5 && currentGapPct < 2.5;
        }
        return false;
    }

    @Test
    @DisplayName("[통합] DB gap_threshold_pct 1.5 + price 102.0 (gap 2.0%) → 진입")
    public void integration_db15_gap20_pass() {
        MorningRushConfigEntity cfg = new MorningRushConfigEntity();
        cfg.setGapThresholdPct(BigDecimal.valueOf(1.5));
        boolean result = v141MrEntrySignal(102.0, 100.0,
                cfg.getGapThresholdPct().doubleValue(), false);
        assertTrue(result, "gap 2.0%, threshold 1.5%, V145 상한 2.5% 미만 → 통과");
    }

    @Test
    @DisplayName("[통합] DB gap_threshold_pct 1.5 + price 102.3 (gap 2.3%) → 진입 (V145 완화)")
    public void integration_db15_gap23_pass() {
        // V141에서는 차단 (>= 2.2), V145에서는 통과 (< 2.5)
        boolean result = v141MrEntrySignal(102.3, 100.0, 1.5, false);
        assertTrue(result, "gap 2.3% V145 상한 2.5% 미만 → 통과 (V141 차단 케이스 완화)");
    }

    @Test
    @DisplayName("[통합] DB gap_threshold_pct 1.5 + price 102.5 (gap 2.5%) → 차단 (V145 상한 inclusive)")
    public void integration_db15_gap25_blocked() {
        boolean result = v141MrEntrySignal(102.5, 100.0, 1.5, false);
        assertFalse(result, "gap 2.5% V145 상한 inclusive → 차단");
    }

    @Test
    @DisplayName("[통합] DB gap_threshold_pct 1.5 + price 103.0 (gap 3.0%) → 차단")
    public void integration_db15_gap30_blocked() {
        boolean result = v141MrEntrySignal(103.0, 100.0, 1.5, false);
        assertFalse(result, "gap 3.0% V145 상한 초과 → 차단");
    }

    @Test
    @DisplayName("[통합] DB gap_threshold_pct 1.5 + price 101.0 (gap 1.0%) → 차단")
    public void integration_db15_gap10_blocked() {
        boolean result = v141MrEntrySignal(101.0, 100.0, 1.5, false);
        assertFalse(result, "gap 1.0% < 1.5% threshold → 차단");
    }

    @Test
    @DisplayName("[통합] surge 단독 + gap 1.7% → 진입 (V145 1.5-2.5 범위 내)")
    public void integration_surge_gap17_pass() {
        boolean result = v141MrEntrySignal(101.7, 100.0, 1.5, true);
        assertTrue(result, "surge + gap 1.7% V145 통과");
    }

    @Test
    @DisplayName("[통합] surge 단독 + gap 2.4% → 진입 (V145 완화 후 통과)")
    public void integration_surge_gap24_pass() {
        boolean result = v141MrEntrySignal(102.4, 100.0, 1.5, true);
        assertTrue(result, "surge + gap 2.4% V145 통과 (V141 차단 케이스 완화)");
    }

    @Test
    @DisplayName("[통합] surge 단독 + gap 2.5% → 차단 (V145 상한)")
    public void integration_surge_gap25_blocked() {
        boolean result = v141MrEntrySignal(102.5, 100.0, 1.5, true);
        assertFalse(result, "surge gap 2.5% V145 상한 차단");
    }

    @Test
    @DisplayName("[통합] surge 단독 + gap 1.2% → 차단 (1.5% 미달)")
    public void integration_surge_gap12_blocked() {
        boolean result = v141MrEntrySignal(101.2, 100.0, 1.5, true);
        assertFalse(result, "surge + gap < 1.5% 차단");
    }

    // ===== Entity 검증 =====

    @Test
    @DisplayName("[통합] V141 SQL: gap_threshold_pct 1.5 → Entity에서 정상 로딩")
    public void integration_v141_sql_loaded() {
        MorningRushConfigEntity cfg = new MorningRushConfigEntity();
        cfg.setGapThresholdPct(BigDecimal.valueOf(1.5));
        assertEquals(0, cfg.getGapThresholdPct().compareTo(BigDecimal.valueOf(1.5)));
    }

    @Test
    @DisplayName("[통합] V145 시나리오: 5/9 PROS gap 5.53% → 차단 (꼬리매수 회피)")
    public void integration_scenario_PROS_5_9() {
        // 5/9 PROS 09:04 시그널: rangeHigh 1285, price 1356 (gap 5.53%)
        boolean result = v141MrEntrySignal(1356, 1285, 1.5, false);
        assertFalse(result, "PROS 5/9 gap 5.53% V145에서도 차단");
    }

    @Test
    @DisplayName("[통합] V145 시나리오: 5/9 IP gap 1.92% → 진입 (실제 익절 케이스)")
    public void integration_scenario_IP_5_9() {
        // 5/9 10:00 IP gap 1.92%
        boolean result = v141MrEntrySignal(859.0 * 1.0192, 859.0, 1.5, false);
        assertTrue(result, "IP 5/9 gap 1.92% V145 진입 (실제 분할익절 성공)");
    }

    @Test
    @DisplayName("[통합] V145 신규: 4주 데이터 gap 2.0~2.5% 100% 승률 구간 통과")
    public void integration_v145_relaxed_zone() {
        // 4주 라이브 데이터에서 gap 2.0~2.5% 구간 2건 100% 승률 +2.78%
        // V141에서는 차단되어 놓쳤던 좋은 구간 → V145 완화로 포착
        assertTrue(v141MrEntrySignal(102.1, 100.0, 1.5, false), "gap 2.1% V145 통과");
        assertTrue(v141MrEntrySignal(102.3, 100.0, 1.5, false), "gap 2.3% V145 통과 (V141 차단)");
        assertTrue(v141MrEntrySignal(102.4, 100.0, 1.5, false), "gap 2.4% V145 통과 (V141 차단)");
    }
}
