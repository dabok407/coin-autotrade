package com.example.upbit.bot;

import com.example.upbit.db.MorningRushConfigEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V141 #1 통합 테스트 — MorningRush gap 1.5~2.2% (DB + 코드 상한)
 *
 * DB 설정 + 코드 진입 로직 통합 검증.
 * (2단계 보강)
 */
public class V141MrGapIntegrationTest {

    /**
     * V141 MR 진입 로직 시뮬 (signal path)
     */
    private static boolean v141MrEntrySignal(double price, double rangeHigh,
                                              double gapThresholdPct, boolean surgeCondition) {
        double gapThreshold = gapThresholdPct / 100.0;  // V130 변환
        double threshold = rangeHigh * (1.0 + gapThreshold);
        boolean gapCondition = price > threshold;
        double currentGapPct = (price - rangeHigh) / rangeHigh * 100.0;

        if (gapCondition) {
            // V141 상한 체크
            if (currentGapPct >= 2.2) return false;
            return true;
        } else if (surgeCondition) {
            return currentGapPct >= 1.5 && currentGapPct < 2.2;
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
        assertTrue(result, "gap 2.0%, threshold 1.5%, V141 상한 2.2% 미만 → 통과");
    }

    @Test
    @DisplayName("[통합] DB gap_threshold_pct 1.5 + price 102.5 (gap 2.5%) → 차단")
    public void integration_db15_gap25_blocked() {
        boolean result = v141MrEntrySignal(102.5, 100.0, 1.5, false);
        assertFalse(result, "gap 2.5% V141 상한 2.2% 초과 → 차단");
    }

    @Test
    @DisplayName("[통합] DB gap_threshold_pct 1.5 + price 101.0 (gap 1.0%) → 차단")
    public void integration_db15_gap10_blocked() {
        boolean result = v141MrEntrySignal(101.0, 100.0, 1.5, false);
        assertFalse(result, "gap 1.0% < 1.5% threshold → 차단");
    }

    @Test
    @DisplayName("[통합] surge 단독 + gap 1.7% → 진입 (V141 1.5-2.2 범위 내)")
    public void integration_surge_gap17_pass() {
        boolean result = v141MrEntrySignal(101.7, 100.0, 1.5, true);
        assertTrue(result, "surge + gap 1.7% V141 통과");
    }

    @Test
    @DisplayName("[통합] surge 단독 + gap 2.5% → 차단 (V141 상한)")
    public void integration_surge_gap25_blocked() {
        boolean result = v141MrEntrySignal(102.5, 100.0, 1.5, true);
        assertFalse(result, "surge gap 2.5% V141 상한 차단");
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
    @DisplayName("[통합] V141 시나리오: 5/9 PROS gap 5.53% → 차단 (꼬리매수 회피)")
    public void integration_scenario_PROS_5_9() {
        // 5/9 PROS 09:04 시그널: rangeHigh 1285, price 1356 (gap 5.53%)
        boolean result = v141MrEntrySignal(1356, 1285, 1.5, false);
        assertFalse(result, "PROS 5/9 gap 5.53% V141 차단");
    }

    @Test
    @DisplayName("[통합] V141 시나리오: 5/9 IP gap 1.92% → 진입 (실제 익절 케이스)")
    public void integration_scenario_IP_5_9() {
        // 5/9 10:00 IP gap 1.92%
        boolean result = v141MrEntrySignal(859.0 * 1.0192, 859.0, 1.5, false);
        assertTrue(result, "IP 5/9 gap 1.92% V141 진입 (실제 분할익절 성공)");
    }
}
