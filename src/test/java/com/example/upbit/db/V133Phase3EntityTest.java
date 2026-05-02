package com.example.upbit.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V133 Phase 3 — Entity 단위 테스트
 *
 * 검증:
 *   1) BEV 보장 — 3 entity getter/setter, default 비활성(V130 호환)
 *   2) Trail Ratchet peak≥5 4.0 → 5.0 setter 검증 (V133 마이그레이션 UPDATE)
 *   3) AD entry_end_hour 22 → 23 setter 검증
 */
@DisplayName("V133 Phase 3 Entity Unit Tests")
public class V133Phase3EntityTest {

    @Test
    @DisplayName("OP Entity: bev_guard_enabled default=false, getter/setter + null")
    public void op_bevGuardDefaultsAndSetters() {
        OpeningScannerConfigEntity cfg = new OpeningScannerConfigEntity();
        assertFalse(cfg.isBevGuardEnabled(), "default false (V130 호환)");
        assertEquals(5.0, cfg.getBevTriggerPct().doubleValue(), 0.001);

        cfg.setBevGuardEnabled(true);
        cfg.setBevTriggerPct(BigDecimal.valueOf(7.0));
        assertTrue(cfg.isBevGuardEnabled());
        assertEquals(7.0, cfg.getBevTriggerPct().doubleValue(), 0.001);

        cfg.setBevTriggerPct(null);
        assertEquals(5.0, cfg.getBevTriggerPct().doubleValue(), 0.001);
    }

    @Test
    @DisplayName("MR Entity: bev_guard_enabled default + getter/setter")
    public void mr_bevGuardDefaultsAndSetters() {
        MorningRushConfigEntity cfg = new MorningRushConfigEntity();
        assertFalse(cfg.isBevGuardEnabled());
        cfg.setBevGuardEnabled(true);
        assertTrue(cfg.isBevGuardEnabled());
    }

    @Test
    @DisplayName("AD Entity: bev_guard_enabled default + getter/setter")
    public void ad_bevGuardDefaultsAndSetters() {
        AllDayScannerConfigEntity cfg = new AllDayScannerConfigEntity();
        assertFalse(cfg.isBevGuardEnabled());
        cfg.setBevGuardEnabled(true);
        cfg.setBevTriggerPct(BigDecimal.valueOf(6.0));
        assertEquals(6.0, cfg.getBevTriggerPct().doubleValue(), 0.001);
    }

    @Test
    @DisplayName("Trail Ratchet peak≥5 4.0 → 5.0 setter 검증 (3 entity)")
    public void allEntities_ratchetAbove5_5pct() {
        OpeningScannerConfigEntity op = new OpeningScannerConfigEntity();
        op.setSplit1stDropAbove5(BigDecimal.valueOf(5.0));
        op.setTrailAfterDropAbove5(BigDecimal.valueOf(5.0));
        assertEquals(5.0, op.getSplit1stDropAbove5().doubleValue(), 0.001);
        assertEquals(5.0, op.getTrailAfterDropAbove5().doubleValue(), 0.001);

        MorningRushConfigEntity mr = new MorningRushConfigEntity();
        mr.setSplit1stDropAbove5(BigDecimal.valueOf(5.0));
        mr.setTrailAfterDropAbove5(BigDecimal.valueOf(5.0));
        assertEquals(5.0, mr.getSplit1stDropAbove5().doubleValue(), 0.001);

        AllDayScannerConfigEntity ad = new AllDayScannerConfigEntity();
        ad.setSplit1stDropAbove5(BigDecimal.valueOf(5.0));
        ad.setTrailAfterDropAbove5(BigDecimal.valueOf(5.0));
        assertEquals(5.0, ad.getSplit1stDropAbove5().doubleValue(), 0.001);
    }

    @Test
    @DisplayName("AD Entity: entry_end_hour 22 → 23 setter (V133 마이그레이션)")
    public void ad_entryEndHour23() {
        AllDayScannerConfigEntity cfg = new AllDayScannerConfigEntity();
        cfg.setEntryEndHour(23);
        cfg.setEntryEndMin(0);
        assertEquals(23, cfg.getEntryEndHour());
        assertEquals(0, cfg.getEntryEndMin());
    }
}
