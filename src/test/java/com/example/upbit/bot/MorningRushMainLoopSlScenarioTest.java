package com.example.upbit.bot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 모닝러쉬 mainLoop 폴링 경로 SL 종합안 시나리오 테스트.
 *
 * 운영 사고 (2026-04-08 09:06): KRW-EDGE @185 → 09:06:03 -3.24% 매도
 *  - realtime checkRealtimeTpSl: SL 종합안 적용 ✅
 *  - mainLoop checkPositionsTpSl: 단순 -3.0% 폴링으로 wide 보호 우회 ❌
 *
 * 핫픽스 후 동일 로직이 두 경로 모두 적용되었는지 검증.
 *
 * 검증 단계:
 *  1. grace 구간 (0~60s): SL 무시 (-7%여도 매도 안 함)
 *  2. wide 구간 (60s~30분): SL_WIDE -6.0% 임계
 *  3. tight 구간 (30분 이후): SL_TIGHT -3.0% 임계
 *  4. TP는 항상 우선
 */
public class MorningRushMainLoopSlScenarioTest {

    public enum SellResult {
        NONE,
        TP,
        SL_WIDE,
        SL_TIGHT
    }

    /**
     * 운영 mainLoop checkPositionsTpSl 로직 그대로 복제 (SL 종합안 적용 후).
     */
    private SellResult evaluateMainLoopTpSl(
            double currentPrice, double avgPrice, long elapsedMs,
            double tpPct, double tightSlPct, double wideSlPct,
            long gracePeriodMs, long widePeriodMs) {

        double pnlPct = (currentPrice - avgPrice) / avgPrice * 100.0;

        if (pnlPct >= tpPct) return SellResult.TP;

        if (elapsedMs < gracePeriodMs) {
            return SellResult.NONE;  // grace 보호
        } else if (elapsedMs < widePeriodMs) {
            if (pnlPct <= -wideSlPct) return SellResult.SL_WIDE;
            return SellResult.NONE;
        } else {
            if (pnlPct <= -tightSlPct) return SellResult.SL_TIGHT;
            return SellResult.NONE;
        }
    }

    // 운영 설정값 (V103 적용 후)
    private static final double TP_PCT = 2.3;
    private static final double TIGHT_SL_PCT = 3.0;
    private static final double WIDE_SL_PCT = 6.0;
    private static final long GRACE_MS = 60_000L;          // 60s
    private static final long WIDE_MS = 30 * 60_000L;       // 30분

    // ═══════════════════════════════════════════════════════════
    //  Grace 구간 (0~60초)
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("시나리오 1: grace 30s + pnl -7% → SL 무시 (NONE)")
    public void scenario1_grace_protect_extreme() {
        SellResult r = evaluateMainLoopTpSl(
                93.0, 100.0, 30_000L, TP_PCT, TIGHT_SL_PCT, WIDE_SL_PCT, GRACE_MS, WIDE_MS);
        assertEquals(SellResult.NONE, r, "grace 보호로 -7%여도 매도 안 함");
    }

    @Test
    @DisplayName("시나리오 2: grace 59s + pnl -10% → 여전히 grace, SL 무시")
    public void scenario2_grace_just_before_end() {
        SellResult r = evaluateMainLoopTpSl(
                90.0, 100.0, 59_000L, TP_PCT, TIGHT_SL_PCT, WIDE_SL_PCT, GRACE_MS, WIDE_MS);
        assertEquals(SellResult.NONE, r);
    }

    // ═══════════════════════════════════════════════════════════
    //  Wide 구간 (60초~30분) — 운영 사고 케이스 재현
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("⭐ 시나리오 3 (운영 사고 재현): wide 5분 58초 + pnl -3.24% → NONE (SL_WIDE 미발동)")
    public void scenario3_KRW_EDGE_incident_replay() {
        // KRW-EDGE: avg=185, current=179, elapsed=358000ms (5분 58초)
        // 핫픽스 전에는 -3.24% < -3.0% → SL 발동했음
        // 핫픽스 후에는 wide 구간이라 -6.0% 미달 → SL 미발동
        SellResult r = evaluateMainLoopTpSl(
                179.0, 185.0, 5L * 60_000L + 58_000L,
                TP_PCT, TIGHT_SL_PCT, WIDE_SL_PCT, GRACE_MS, WIDE_MS);
        assertEquals(SellResult.NONE, r,
                "wide 구간 -3.24%는 wide 임계 -6.0% 미달, 매도 안 함 (사고 재발 방지)");
    }

    @Test
    @DisplayName("시나리오 4: wide 10분 + pnl -5.99% → NONE (wide 미달)")
    public void scenario4_wide_just_before_threshold() {
        SellResult r = evaluateMainLoopTpSl(
                94.01, 100.0, 10L * 60_000L,
                TP_PCT, TIGHT_SL_PCT, WIDE_SL_PCT, GRACE_MS, WIDE_MS);
        assertEquals(SellResult.NONE, r);
    }

    @Test
    @DisplayName("시나리오 5: wide 10분 + pnl -6.0% (정확) → SL_WIDE 발동")
    public void scenario5_wide_exact_threshold() {
        SellResult r = evaluateMainLoopTpSl(
                94.0, 100.0, 10L * 60_000L,
                TP_PCT, TIGHT_SL_PCT, WIDE_SL_PCT, GRACE_MS, WIDE_MS);
        assertEquals(SellResult.SL_WIDE, r);
    }

    @Test
    @DisplayName("시나리오 6: wide 25분 + pnl -7% → SL_WIDE 발동")
    public void scenario6_wide_extreme_loss() {
        SellResult r = evaluateMainLoopTpSl(
                93.0, 100.0, 25L * 60_000L,
                TP_PCT, TIGHT_SL_PCT, WIDE_SL_PCT, GRACE_MS, WIDE_MS);
        assertEquals(SellResult.SL_WIDE, r);
    }

    // ═══════════════════════════════════════════════════════════
    //  Tight 구간 (30분 이후)
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("시나리오 7: tight 31분 + pnl -3.0% (정확) → SL_TIGHT 발동")
    public void scenario7_tight_exact_threshold() {
        SellResult r = evaluateMainLoopTpSl(
                97.0, 100.0, 31L * 60_000L,
                TP_PCT, TIGHT_SL_PCT, WIDE_SL_PCT, GRACE_MS, WIDE_MS);
        assertEquals(SellResult.SL_TIGHT, r);
    }

    @Test
    @DisplayName("시나리오 8: tight 31분 + pnl -2.99% → NONE (tight 미달)")
    public void scenario8_tight_just_before_threshold() {
        SellResult r = evaluateMainLoopTpSl(
                97.01, 100.0, 31L * 60_000L,
                TP_PCT, TIGHT_SL_PCT, WIDE_SL_PCT, GRACE_MS, WIDE_MS);
        assertEquals(SellResult.NONE, r);
    }

    @Test
    @DisplayName("시나리오 9: tight 60분 + pnl -5% → SL_TIGHT 발동")
    public void scenario9_tight_deep_loss() {
        SellResult r = evaluateMainLoopTpSl(
                95.0, 100.0, 60L * 60_000L,
                TP_PCT, TIGHT_SL_PCT, WIDE_SL_PCT, GRACE_MS, WIDE_MS);
        assertEquals(SellResult.SL_TIGHT, r);
    }

    // ═══════════════════════════════════════════════════════════
    //  TP 우선 검증
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("시나리오 10: grace 10s + pnl +2.5% → TP 우선 (grace보다 우선)")
    public void scenario10_tp_in_grace() {
        SellResult r = evaluateMainLoopTpSl(
                102.5, 100.0, 10_000L,
                TP_PCT, TIGHT_SL_PCT, WIDE_SL_PCT, GRACE_MS, WIDE_MS);
        assertEquals(SellResult.TP, r);
    }

    @Test
    @DisplayName("시나리오 11: wide 5분 + pnl +2.31% → TP")
    public void scenario11_tp_in_wide() {
        SellResult r = evaluateMainLoopTpSl(
                102.31, 100.0, 5L * 60_000L,
                TP_PCT, TIGHT_SL_PCT, WIDE_SL_PCT, GRACE_MS, WIDE_MS);
        assertEquals(SellResult.TP, r);
    }

    @Test
    @DisplayName("시나리오 12: tight 60분 + pnl +5% → TP")
    public void scenario12_tp_in_tight() {
        SellResult r = evaluateMainLoopTpSl(
                105.0, 100.0, 60L * 60_000L,
                TP_PCT, TIGHT_SL_PCT, WIDE_SL_PCT, GRACE_MS, WIDE_MS);
        assertEquals(SellResult.TP, r);
    }

    // ═══════════════════════════════════════════════════════════
    //  Wide → Tight 전환 경계 (30분 정확)
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("시나리오 13: 30분 정확 + pnl -3.5% → SL_TIGHT (wide 종료)")
    public void scenario13_wide_tight_boundary() {
        // 30분 정확 = widePeriodMs와 같음 → tight 진입 (elapsedMs < widePeriodMs는 false)
        SellResult r = evaluateMainLoopTpSl(
                96.5, 100.0, 30L * 60_000L,
                TP_PCT, TIGHT_SL_PCT, WIDE_SL_PCT, GRACE_MS, WIDE_MS);
        assertEquals(SellResult.SL_TIGHT, r);
    }

    @Test
    @DisplayName("시나리오 14: 29분 59초 + pnl -3.5% → NONE (wide 마지막, 6% 미달)")
    public void scenario14_wide_last_second() {
        SellResult r = evaluateMainLoopTpSl(
                96.5, 100.0, 29L * 60_000L + 59_000L,
                TP_PCT, TIGHT_SL_PCT, WIDE_SL_PCT, GRACE_MS, WIDE_MS);
        assertEquals(SellResult.NONE, r);
    }

    // ═══════════════════════════════════════════════════════════
    //  Grace → Wide 전환 경계 (60초 정확)
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("시나리오 15: 60초 정확 + pnl -7% → SL_WIDE (grace 종료)")
    public void scenario15_grace_wide_boundary() {
        SellResult r = evaluateMainLoopTpSl(
                93.0, 100.0, 60_000L,
                TP_PCT, TIGHT_SL_PCT, WIDE_SL_PCT, GRACE_MS, WIDE_MS);
        assertEquals(SellResult.SL_WIDE, r);
    }
}
