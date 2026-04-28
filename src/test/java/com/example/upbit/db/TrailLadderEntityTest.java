package com.example.upbit.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V130 ①: Trail Ladder A — Entity getDropForPeak 단위 테스트.
 *
 * 3 Entity (MR/OP/AD) 모두 검증:
 *   - 4 구간 분기 정확성 (isAfterSplit=false/true)
 *   - trail_ladder_enabled=false 시 기존 단일값 fallback
 *   - 경계값(정확히 2.0, 3.0, 5.0) 처리
 */
@DisplayName("V130 Trail Ladder Entity Unit Tests")
public class TrailLadderEntityTest {

    // ─────────────────────────────────────────────────────────────────
    //  MorningRushConfigEntity
    // ─────────────────────────────────────────────────────────────────

    private MorningRushConfigEntity buildMrCfg() {
        MorningRushConfigEntity cfg = new MorningRushConfigEntity();
        cfg.setTrailLadderEnabled(true);
        cfg.setSplit1stDropUnder2(BigDecimal.valueOf(0.50));
        cfg.setSplit1stDropUnder3(BigDecimal.valueOf(1.00));
        cfg.setSplit1stDropUnder5(BigDecimal.valueOf(1.50));
        cfg.setSplit1stDropAbove5(BigDecimal.valueOf(2.00));
        cfg.setTrailAfterDropUnder2(BigDecimal.valueOf(1.00));
        cfg.setTrailAfterDropUnder3(BigDecimal.valueOf(1.20));
        cfg.setTrailAfterDropUnder5(BigDecimal.valueOf(1.50));
        cfg.setTrailAfterDropAbove5(BigDecimal.valueOf(2.00));
        cfg.setSplit1stTrailDrop(BigDecimal.valueOf(0.5));
        cfg.setTrailDropAfterSplit(BigDecimal.valueOf(1.2));
        return cfg;
    }

    @Test
    @DisplayName("MR: peak<2 (1.5%) → SPLIT_1ST drop=0.5, AfterSplit drop=1.0")
    public void mr_peakUnder2_correctDrop() {
        MorningRushConfigEntity cfg = buildMrCfg();
        assertEquals(0.50, cfg.getDropForPeak(1.5, false).doubleValue(), 0.001,
                "peak 1.5% → SPLIT_1ST under2=0.50");
        assertEquals(1.00, cfg.getDropForPeak(1.5, true).doubleValue(), 0.001,
                "peak 1.5% → AfterSplit under2=1.00");
    }

    @Test
    @DisplayName("MR: peak 2~3 (2.5%) → SPLIT_1ST drop=1.0, AfterSplit drop=1.2")
    public void mr_peakUnder3_correctDrop() {
        MorningRushConfigEntity cfg = buildMrCfg();
        assertEquals(1.00, cfg.getDropForPeak(2.5, false).doubleValue(), 0.001,
                "peak 2.5% → SPLIT_1ST under3=1.00");
        assertEquals(1.20, cfg.getDropForPeak(2.5, true).doubleValue(), 0.001,
                "peak 2.5% → AfterSplit under3=1.20");
    }

    @Test
    @DisplayName("MR: peak 3~5 (4.0%) → SPLIT_1ST drop=1.5, AfterSplit drop=1.5")
    public void mr_peakUnder5_correctDrop() {
        MorningRushConfigEntity cfg = buildMrCfg();
        assertEquals(1.50, cfg.getDropForPeak(4.0, false).doubleValue(), 0.001,
                "peak 4.0% → SPLIT_1ST under5=1.50");
        assertEquals(1.50, cfg.getDropForPeak(4.0, true).doubleValue(), 0.001,
                "peak 4.0% → AfterSplit under5=1.50");
    }

    @Test
    @DisplayName("MR: peak>=5 (6.0%) → SPLIT_1ST drop=2.0, AfterSplit drop=2.0")
    public void mr_peakAbove5_correctDrop() {
        MorningRushConfigEntity cfg = buildMrCfg();
        assertEquals(2.00, cfg.getDropForPeak(6.0, false).doubleValue(), 0.001,
                "peak 6.0% → SPLIT_1ST above5=2.00");
        assertEquals(2.00, cfg.getDropForPeak(6.0, true).doubleValue(), 0.001,
                "peak 6.0% → AfterSplit above5=2.00");
    }

    @Test
    @DisplayName("MR: trail_ladder_enabled=false → 기존 단일값 fallback")
    public void mr_ladderDisabled_fallbackToSingleValue() {
        MorningRushConfigEntity cfg = buildMrCfg();
        cfg.setTrailLadderEnabled(false);
        // 어떤 peak 값이어도 단일값 반환
        assertEquals(0.5, cfg.getDropForPeak(0.5, false).doubleValue(), 0.001,
                "disabled: SPLIT_1ST fallback=0.5");
        assertEquals(1.2, cfg.getDropForPeak(10.0, true).doubleValue(), 0.001,
                "disabled: AfterSplit fallback=1.2");
        assertEquals(0.5, cfg.getDropForPeak(6.0, false).doubleValue(), 0.001,
                "disabled: peak>=5도 단일값 사용");
    }

    @Test
    @DisplayName("MR: 경계값 peakPct=2.0 정확히 → under3 구간")
    public void mr_boundary_exactly2_isUnder3() {
        MorningRushConfigEntity cfg = buildMrCfg();
        // peakPct < 2 이므로 peakPct=2.0은 under3 구간
        assertEquals(1.00, cfg.getDropForPeak(2.0, false).doubleValue(), 0.001,
                "peak=2.0(경계) → under3=1.00 (< 2 는 false)");
    }

    @Test
    @DisplayName("MR: 경계값 peakPct=5.0 정확히 → above5 구간")
    public void mr_boundary_exactly5_isAbove5() {
        MorningRushConfigEntity cfg = buildMrCfg();
        assertEquals(2.00, cfg.getDropForPeak(5.0, false).doubleValue(), 0.001,
                "peak=5.0(경계) → above5=2.00 (< 5 는 false)");
    }

    // ─────────────────────────────────────────────────────────────────
    //  OpeningScannerConfigEntity
    // ─────────────────────────────────────────────────────────────────

    private OpeningScannerConfigEntity buildOpCfg() {
        OpeningScannerConfigEntity cfg = new OpeningScannerConfigEntity();
        cfg.setTrailLadderEnabled(true);
        cfg.setSplit1stDropUnder2(BigDecimal.valueOf(0.50));
        cfg.setSplit1stDropUnder3(BigDecimal.valueOf(1.00));
        cfg.setSplit1stDropUnder5(BigDecimal.valueOf(1.50));
        cfg.setSplit1stDropAbove5(BigDecimal.valueOf(2.00));
        cfg.setTrailAfterDropUnder2(BigDecimal.valueOf(1.00));
        cfg.setTrailAfterDropUnder3(BigDecimal.valueOf(1.20));
        cfg.setTrailAfterDropUnder5(BigDecimal.valueOf(1.50));
        cfg.setTrailAfterDropAbove5(BigDecimal.valueOf(2.00));
        cfg.setSplit1stTrailDrop(BigDecimal.valueOf(0.5));
        cfg.setTrailDropAfterSplit(BigDecimal.valueOf(1.2));
        return cfg;
    }

    @Test
    @DisplayName("OP: peak<2 (1.5%) → SPLIT_1ST drop=0.5, AfterSplit drop=1.0")
    public void op_peakUnder2_correctDrop() {
        OpeningScannerConfigEntity cfg = buildOpCfg();
        assertEquals(0.50, cfg.getDropForPeak(1.5, false).doubleValue(), 0.001);
        assertEquals(1.00, cfg.getDropForPeak(1.5, true).doubleValue(), 0.001);
    }

    @Test
    @DisplayName("OP: peak 2~3 (2.5%) → SPLIT_1ST drop=1.0, AfterSplit drop=1.2")
    public void op_peakUnder3_correctDrop() {
        OpeningScannerConfigEntity cfg = buildOpCfg();
        assertEquals(1.00, cfg.getDropForPeak(2.5, false).doubleValue(), 0.001);
        assertEquals(1.20, cfg.getDropForPeak(2.5, true).doubleValue(), 0.001);
    }

    @Test
    @DisplayName("OP: peak 3~5 (4.0%) → SPLIT_1ST drop=1.5, AfterSplit drop=1.5")
    public void op_peakUnder5_correctDrop() {
        OpeningScannerConfigEntity cfg = buildOpCfg();
        assertEquals(1.50, cfg.getDropForPeak(4.0, false).doubleValue(), 0.001);
        assertEquals(1.50, cfg.getDropForPeak(4.0, true).doubleValue(), 0.001);
    }

    @Test
    @DisplayName("OP: peak>=5 (6.0%) → SPLIT_1ST drop=2.0, AfterSplit drop=2.0")
    public void op_peakAbove5_correctDrop() {
        OpeningScannerConfigEntity cfg = buildOpCfg();
        assertEquals(2.00, cfg.getDropForPeak(6.0, false).doubleValue(), 0.001);
        assertEquals(2.00, cfg.getDropForPeak(6.0, true).doubleValue(), 0.001);
    }

    @Test
    @DisplayName("OP: trail_ladder_enabled=false → 기존 단일값 fallback")
    public void op_ladderDisabled_fallbackToSingleValue() {
        OpeningScannerConfigEntity cfg = buildOpCfg();
        cfg.setTrailLadderEnabled(false);
        assertEquals(0.5, cfg.getDropForPeak(0.5, false).doubleValue(), 0.001);
        assertEquals(1.2, cfg.getDropForPeak(10.0, true).doubleValue(), 0.001);
    }

    @Test
    @DisplayName("OP: 커스텀 구간값 설정 후 정확히 반환")
    public void op_customBandValues_areReturned() {
        OpeningScannerConfigEntity cfg = buildOpCfg();
        cfg.setSplit1stDropUnder2(BigDecimal.valueOf(0.30));
        cfg.setSplit1stDropAbove5(BigDecimal.valueOf(3.00));
        assertEquals(0.30, cfg.getDropForPeak(1.0, false).doubleValue(), 0.001,
                "커스텀 under2=0.30");
        assertEquals(3.00, cfg.getDropForPeak(7.0, false).doubleValue(), 0.001,
                "커스텀 above5=3.00");
    }

    // ─────────────────────────────────────────────────────────────────
    //  AllDayScannerConfigEntity
    // ─────────────────────────────────────────────────────────────────

    private AllDayScannerConfigEntity buildAdCfg() {
        AllDayScannerConfigEntity cfg = new AllDayScannerConfigEntity();
        cfg.setTrailLadderEnabled(true);
        cfg.setSplit1stDropUnder2(BigDecimal.valueOf(0.50));
        cfg.setSplit1stDropUnder3(BigDecimal.valueOf(1.00));
        cfg.setSplit1stDropUnder5(BigDecimal.valueOf(1.50));
        cfg.setSplit1stDropAbove5(BigDecimal.valueOf(2.00));
        cfg.setTrailAfterDropUnder2(BigDecimal.valueOf(1.00));
        cfg.setTrailAfterDropUnder3(BigDecimal.valueOf(1.20));
        cfg.setTrailAfterDropUnder5(BigDecimal.valueOf(1.50));
        cfg.setTrailAfterDropAbove5(BigDecimal.valueOf(2.00));
        cfg.setSplit1stTrailDrop(BigDecimal.valueOf(0.5));
        cfg.setTrailDropAfterSplit(BigDecimal.valueOf(1.2));
        return cfg;
    }

    @Test
    @DisplayName("AD: peak<2 (1.5%) → SPLIT_1ST drop=0.5, AfterSplit drop=1.0")
    public void ad_peakUnder2_correctDrop() {
        AllDayScannerConfigEntity cfg = buildAdCfg();
        assertEquals(0.50, cfg.getDropForPeak(1.5, false).doubleValue(), 0.001);
        assertEquals(1.00, cfg.getDropForPeak(1.5, true).doubleValue(), 0.001);
    }

    @Test
    @DisplayName("AD: peak 2~3 (2.5%) → SPLIT_1ST drop=1.0, AfterSplit drop=1.2")
    public void ad_peakUnder3_correctDrop() {
        AllDayScannerConfigEntity cfg = buildAdCfg();
        assertEquals(1.00, cfg.getDropForPeak(2.5, false).doubleValue(), 0.001);
        assertEquals(1.20, cfg.getDropForPeak(2.5, true).doubleValue(), 0.001);
    }

    @Test
    @DisplayName("AD: peak 3~5 (4.0%) → SPLIT_1ST drop=1.5, AfterSplit drop=1.5")
    public void ad_peakUnder5_correctDrop() {
        AllDayScannerConfigEntity cfg = buildAdCfg();
        assertEquals(1.50, cfg.getDropForPeak(4.0, false).doubleValue(), 0.001);
        assertEquals(1.50, cfg.getDropForPeak(4.0, true).doubleValue(), 0.001);
    }

    @Test
    @DisplayName("AD: peak>=5 (6.0%) → SPLIT_1ST drop=2.0, AfterSplit drop=2.0")
    public void ad_peakAbove5_correctDrop() {
        AllDayScannerConfigEntity cfg = buildAdCfg();
        assertEquals(2.00, cfg.getDropForPeak(6.0, false).doubleValue(), 0.001);
        assertEquals(2.00, cfg.getDropForPeak(6.0, true).doubleValue(), 0.001);
    }

    @Test
    @DisplayName("AD: trail_ladder_enabled=false → 기존 단일값 fallback")
    public void ad_ladderDisabled_fallbackToSingleValue() {
        AllDayScannerConfigEntity cfg = buildAdCfg();
        cfg.setTrailLadderEnabled(false);
        assertEquals(0.5, cfg.getDropForPeak(0.5, false).doubleValue(), 0.001);
        assertEquals(1.2, cfg.getDropForPeak(10.0, true).doubleValue(), 0.001);
    }

    @Test
    @DisplayName("AD: 경계값 peakPct=3.0 정확히 → under5 구간")
    public void ad_boundary_exactly3_isUnder5() {
        AllDayScannerConfigEntity cfg = buildAdCfg();
        // peakPct=3.0 → (< 3 is false) → under5 구간
        assertEquals(1.50, cfg.getDropForPeak(3.0, false).doubleValue(), 0.001,
                "peak=3.0(경계) → under5=1.50");
    }

    @Test
    @DisplayName("AD: 세 Entity 모두 getDropForPeak 시그니처 동일 (3 스캐너 일관성)")
    public void allThreeEntities_sameDropValues_forSamePeak() {
        MorningRushConfigEntity mr = buildMrCfg();
        OpeningScannerConfigEntity op = buildOpCfg();
        AllDayScannerConfigEntity ad = buildAdCfg();

        double[] peaks = {1.5, 2.5, 4.0, 6.0};
        for (double peak : peaks) {
            double mrVal = mr.getDropForPeak(peak, false).doubleValue();
            double opVal = op.getDropForPeak(peak, false).doubleValue();
            double adVal = ad.getDropForPeak(peak, false).doubleValue();
            assertEquals(mrVal, opVal, 0.001,
                    "peak=" + peak + ": MR vs OP SPLIT_1ST 값 불일치");
            assertEquals(mrVal, adVal, 0.001,
                    "peak=" + peak + ": MR vs AD SPLIT_1ST 값 불일치");
        }
    }
}
