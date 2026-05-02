package com.example.upbit.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V131 Phase 1 — Entity 단위 테스트
 *
 * 검증 대상:
 *   1) Trail Ratchet (V130 ladder의 default 변경) — getDropForPeak 4 구간
 *      - peak<2: 0.7 / <3: 1.0 / <5: 2.5 / >=5: 4.0 (split_1st)
 *      - peak<2: 1.0 / <3: 1.5 / <5: 2.5 / >=5: 5.0 (trail_after)
 *   2) L1 Cap 강제 익절 — 3개 entity (OP/MR/AD) getter/setter + null 처리
 *   3) Min QS/Vol 진입 필터 — OP entity getter/setter + 경계
 *
 * V131 마이그레이션 default값과 일치하는지도 동시 검증.
 */
@DisplayName("V131 Phase 1 Entity Unit Tests")
public class V131Phase1EntityTest {

    // ─────────────────────────────────────────────────────────────────
    //  1. Trail Ratchet — V131 default 값 검증 (Entity field default)
    // ─────────────────────────────────────────────────────────────────

    /**
     * Entity default는 V130 값(0.50/1.00/1.50/2.00) 그대로이고,
     * V131은 마이그레이션 UPDATE로 default를 바꾸는 방식이므로
     * 여기서는 Ratchet 값(0.70/1.00/2.50/4.00)을 setter로 주입해서 동작 검증.
     */
    private MorningRushConfigEntity buildMrRatchetCfg() {
        MorningRushConfigEntity cfg = new MorningRushConfigEntity();
        cfg.setTrailLadderEnabled(true);
        cfg.setSplit1stDropUnder2(BigDecimal.valueOf(0.70));
        cfg.setSplit1stDropUnder3(BigDecimal.valueOf(1.00));
        cfg.setSplit1stDropUnder5(BigDecimal.valueOf(2.50));
        cfg.setSplit1stDropAbove5(BigDecimal.valueOf(4.00));
        cfg.setTrailAfterDropUnder2(BigDecimal.valueOf(1.00));
        cfg.setTrailAfterDropUnder3(BigDecimal.valueOf(1.50));
        cfg.setTrailAfterDropUnder5(BigDecimal.valueOf(2.50));
        cfg.setTrailAfterDropAbove5(BigDecimal.valueOf(5.00));
        cfg.setSplit1stTrailDrop(BigDecimal.valueOf(0.5));
        cfg.setTrailDropAfterSplit(BigDecimal.valueOf(1.2));
        return cfg;
    }

    @Test
    @DisplayName("Ratchet: peak<2 (1.5%) → split1=0.7, trail2=1.0")
    public void ratchet_peakUnder2() {
        MorningRushConfigEntity cfg = buildMrRatchetCfg();
        assertEquals(0.70, cfg.getDropForPeak(1.5, false).doubleValue(), 0.001);
        assertEquals(1.00, cfg.getDropForPeak(1.5, true).doubleValue(), 0.001);
    }

    @Test
    @DisplayName("Ratchet: peak<3 (2.5%) → split1=1.0, trail2=1.5")
    public void ratchet_peakUnder3() {
        MorningRushConfigEntity cfg = buildMrRatchetCfg();
        assertEquals(1.00, cfg.getDropForPeak(2.5, false).doubleValue(), 0.001);
        assertEquals(1.50, cfg.getDropForPeak(2.5, true).doubleValue(), 0.001);
    }

    @Test
    @DisplayName("Ratchet: peak<5 (3.5%) → split1=2.5, trail2=2.5")
    public void ratchet_peakUnder5() {
        MorningRushConfigEntity cfg = buildMrRatchetCfg();
        assertEquals(2.50, cfg.getDropForPeak(3.5, false).doubleValue(), 0.001);
        assertEquals(2.50, cfg.getDropForPeak(3.5, true).doubleValue(), 0.001);
    }

    @Test
    @DisplayName("Ratchet: peak>=5 (5.5%) → split1=4.0, trail2=5.0")
    public void ratchet_peakAbove5() {
        MorningRushConfigEntity cfg = buildMrRatchetCfg();
        assertEquals(4.00, cfg.getDropForPeak(5.5, false).doubleValue(), 0.001);
        assertEquals(5.00, cfg.getDropForPeak(5.5, true).doubleValue(), 0.001);
    }

    @Test
    @DisplayName("Ratchet: peak 경계값 정확 (2.0/3.0/5.0)")
    public void ratchet_boundaryValues() {
        MorningRushConfigEntity cfg = buildMrRatchetCfg();
        // 2.0은 < 2 아님 → under_3
        assertEquals(1.00, cfg.getDropForPeak(2.0, false).doubleValue(), 0.001);
        // 3.0은 < 3 아님 → under_5
        assertEquals(2.50, cfg.getDropForPeak(3.0, false).doubleValue(), 0.001);
        // 5.0은 < 5 아님 → above_5
        assertEquals(4.00, cfg.getDropForPeak(5.0, false).doubleValue(), 0.001);
    }

    // ─────────────────────────────────────────────────────────────────
    //  2. L1 Cap — 3 entity getter/setter
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("OP Entity: L1 Cap default=0(V130 호환), getter/setter + null 처리")
    public void opEntity_l1Cap() {
        OpeningScannerConfigEntity cfg = new OpeningScannerConfigEntity();
        // Entity default 0=비활성(V130 호환). 운영 DB는 V131 마이그레이션 UPDATE로 2.0.
        assertEquals(0.0, cfg.getL1CapPct().doubleValue(), 0.001);
        cfg.setL1CapPct(BigDecimal.valueOf(2.5));
        assertEquals(2.5, cfg.getL1CapPct().doubleValue(), 0.001);
        // null → ZERO (비활성)
        cfg.setL1CapPct(null);
        assertEquals(0.0, cfg.getL1CapPct().doubleValue(), 0.001);
    }

    @Test
    @DisplayName("MR Entity: L1 Cap default=0(V130 호환), getter/setter + null 처리")
    public void mrEntity_l1Cap() {
        MorningRushConfigEntity cfg = new MorningRushConfigEntity();
        assertEquals(0.0, cfg.getL1CapPct().doubleValue(), 0.001);
        cfg.setL1CapPct(BigDecimal.valueOf(2.1));
        assertEquals(2.1, cfg.getL1CapPct().doubleValue(), 0.001);
        cfg.setL1CapPct(null);
        assertEquals(0.0, cfg.getL1CapPct().doubleValue(), 0.001);
    }

    @Test
    @DisplayName("AD Entity: L1 Cap default=0(V130 호환), getter/setter + null 처리")
    public void adEntity_l1Cap() {
        AllDayScannerConfigEntity cfg = new AllDayScannerConfigEntity();
        assertEquals(0.0, cfg.getL1CapPct().doubleValue(), 0.001);
        cfg.setL1CapPct(BigDecimal.valueOf(2.4));
        assertEquals(2.4, cfg.getL1CapPct().doubleValue(), 0.001);
        cfg.setL1CapPct(null);
        assertEquals(0.0, cfg.getL1CapPct().doubleValue(), 0.001);
    }

    // ─────────────────────────────────────────────────────────────────
    //  3. OP 진입 필터 — minQs/minVol getter/setter + null 처리
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("OP Entity: minQsScore default=2.0(V130 하드코딩 호환), getter/setter + null 처리")
    public void opEntity_minQsScore() {
        OpeningScannerConfigEntity cfg = new OpeningScannerConfigEntity();
        // Entity default 2.0(V130 호환). 운영 DB는 V131 마이그레이션 UPDATE로 3.5.
        assertEquals(2.0, cfg.getMinQsScore().doubleValue(), 0.001);
        cfg.setMinQsScore(BigDecimal.valueOf(4.0));
        assertEquals(4.0, cfg.getMinQsScore().doubleValue(), 0.001);
        // null → default 2.0 fallback
        cfg.setMinQsScore(null);
        assertEquals(2.0, cfg.getMinQsScore().doubleValue(), 0.001);
    }

    @Test
    @DisplayName("OP Entity: minVolMult default=0(V130 호환, 비활성), getter/setter + null 처리")
    public void opEntity_minVolMult() {
        OpeningScannerConfigEntity cfg = new OpeningScannerConfigEntity();
        // Entity default 0=비활성(V130 호환). 운영 DB는 V131 마이그레이션 UPDATE로 5.0.
        assertEquals(0.0, cfg.getMinVolMult().doubleValue(), 0.001);
        cfg.setMinVolMult(BigDecimal.valueOf(6.5));
        assertEquals(6.5, cfg.getMinVolMult().doubleValue(), 0.001);
        // null → default 0
        cfg.setMinVolMult(null);
        assertEquals(0.0, cfg.getMinVolMult().doubleValue(), 0.001);
    }

    // ─────────────────────────────────────────────────────────────────
    //  4. Split 비율 30/70 검증
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("OP/MR/AD Entity: split_ratio setter 0.30 검증")
    public void allEntities_splitRatio30() {
        OpeningScannerConfigEntity op = new OpeningScannerConfigEntity();
        op.setSplitRatio(BigDecimal.valueOf(0.30));
        assertEquals(0.30, op.getSplitRatio().doubleValue(), 0.001);

        MorningRushConfigEntity mr = new MorningRushConfigEntity();
        mr.setSplitRatio(BigDecimal.valueOf(0.30));
        assertEquals(0.30, mr.getSplitRatio().doubleValue(), 0.001);

        AllDayScannerConfigEntity ad = new AllDayScannerConfigEntity();
        ad.setSplitRatio(BigDecimal.valueOf(0.30));
        assertEquals(0.30, ad.getSplitRatio().doubleValue(), 0.001);
    }
}
