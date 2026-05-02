package com.example.upbit.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V132 Phase 2 — Entity 단위 테스트
 *
 * 검증:
 *   1) 동적 ATR SL config 컬럼 (3 entity) — getter/setter + null
 *   2) computeDynamicSlPct() — clamp 동작 (min/max), enabled=false fallback
 *   3) AD entry_start_hour=9 setter 검증 (V132 마이그레이션 UPDATE 호환)
 *   4) AD min_confidence=9.0 setter 검증
 *
 * Entity default는 V130 호환(sl_atr_enabled=false). Service 적용은 Phase 2b.
 */
@DisplayName("V132 Phase 2 Entity Unit Tests")
public class V132Phase2EntityTest {

    // ─────────────────────────────────────────────────────────────────
    //  1. ATR SL Entity 컬럼 (3 entity)
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("OP Entity: sl_atr_* default + getter/setter")
    public void op_atrSlDefaultsAndSetters() {
        OpeningScannerConfigEntity cfg = new OpeningScannerConfigEntity();
        assertFalse(cfg.isSlAtrEnabled(), "default sl_atr_enabled=false (V130 호환)");
        assertEquals(1.5, cfg.getSlAtrMult().doubleValue(), 0.001);
        assertEquals(1.5, cfg.getSlAtrMinPct().doubleValue(), 0.001);
        assertEquals(3.5, cfg.getSlAtrMaxPct().doubleValue(), 0.001);

        cfg.setSlAtrEnabled(true);
        cfg.setSlAtrMult(BigDecimal.valueOf(2.0));
        cfg.setSlAtrMinPct(BigDecimal.valueOf(2.0));
        cfg.setSlAtrMaxPct(BigDecimal.valueOf(4.0));
        assertTrue(cfg.isSlAtrEnabled());
        assertEquals(2.0, cfg.getSlAtrMult().doubleValue(), 0.001);
        assertEquals(2.0, cfg.getSlAtrMinPct().doubleValue(), 0.001);
        assertEquals(4.0, cfg.getSlAtrMaxPct().doubleValue(), 0.001);

        // null fallback
        cfg.setSlAtrMult(null);
        cfg.setSlAtrMinPct(null);
        cfg.setSlAtrMaxPct(null);
        assertEquals(1.5, cfg.getSlAtrMult().doubleValue(), 0.001);
        assertEquals(1.5, cfg.getSlAtrMinPct().doubleValue(), 0.001);
        assertEquals(3.5, cfg.getSlAtrMaxPct().doubleValue(), 0.001);
    }

    @Test
    @DisplayName("MR Entity: sl_atr_* default + getter/setter")
    public void mr_atrSlDefaultsAndSetters() {
        MorningRushConfigEntity cfg = new MorningRushConfigEntity();
        assertFalse(cfg.isSlAtrEnabled());
        assertEquals(1.5, cfg.getSlAtrMult().doubleValue(), 0.001);
        cfg.setSlAtrMult(BigDecimal.valueOf(1.8));
        assertEquals(1.8, cfg.getSlAtrMult().doubleValue(), 0.001);
    }

    @Test
    @DisplayName("AD Entity: sl_atr_* default + getter/setter")
    public void ad_atrSlDefaultsAndSetters() {
        AllDayScannerConfigEntity cfg = new AllDayScannerConfigEntity();
        assertFalse(cfg.isSlAtrEnabled());
        cfg.setSlAtrEnabled(true);
        assertTrue(cfg.isSlAtrEnabled());
        assertEquals(3.5, cfg.getSlAtrMaxPct().doubleValue(), 0.001);
    }

    // ─────────────────────────────────────────────────────────────────
    //  2. computeDynamicSlPct() 동작 검증
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("computeDynamicSlPct: enabled=false → fallback 그대로 반환")
    public void computeDynamicSl_disabled_returnsFallback() {
        OpeningScannerConfigEntity cfg = new OpeningScannerConfigEntity();
        // enabled=false (default)
        assertEquals(2.5, cfg.computeDynamicSlPct(1.5, 2.5), 0.001,
                "disabled → fallback 2.5 그대로");
        assertEquals(2.5, cfg.computeDynamicSlPct(0, 2.5), 0.001,
                "atrPct=0 → fallback");
        assertEquals(2.5, cfg.computeDynamicSlPct(-1.0, 2.5), 0.001,
                "atrPct 음수 → fallback");
    }

    @Test
    @DisplayName("computeDynamicSlPct: enabled=true, atr=1.0%, mult=1.5 → 1.5%")
    public void computeDynamicSl_normalCase() {
        OpeningScannerConfigEntity cfg = new OpeningScannerConfigEntity();
        cfg.setSlAtrEnabled(true);
        cfg.setSlAtrMult(BigDecimal.valueOf(1.5));
        cfg.setSlAtrMinPct(BigDecimal.valueOf(1.5));
        cfg.setSlAtrMaxPct(BigDecimal.valueOf(3.5));
        // 1.0 * 1.5 = 1.5 → min과 같으므로 1.5
        assertEquals(1.5, cfg.computeDynamicSlPct(1.0, 2.5), 0.001);
    }

    @Test
    @DisplayName("computeDynamicSlPct: ATR 큰 경우 → max로 clamp")
    public void computeDynamicSl_clampMax() {
        OpeningScannerConfigEntity cfg = new OpeningScannerConfigEntity();
        cfg.setSlAtrEnabled(true);
        cfg.setSlAtrMult(BigDecimal.valueOf(1.5));
        cfg.setSlAtrMinPct(BigDecimal.valueOf(1.5));
        cfg.setSlAtrMaxPct(BigDecimal.valueOf(3.5));
        // 3.0 * 1.5 = 4.5 → max 3.5로 cap
        assertEquals(3.5, cfg.computeDynamicSlPct(3.0, 2.5), 0.001);
        // 5.0 * 1.5 = 7.5 → max 3.5
        assertEquals(3.5, cfg.computeDynamicSlPct(5.0, 2.5), 0.001);
    }

    @Test
    @DisplayName("computeDynamicSlPct: ATR 작은 경우 → min으로 clamp")
    public void computeDynamicSl_clampMin() {
        OpeningScannerConfigEntity cfg = new OpeningScannerConfigEntity();
        cfg.setSlAtrEnabled(true);
        cfg.setSlAtrMult(BigDecimal.valueOf(1.5));
        cfg.setSlAtrMinPct(BigDecimal.valueOf(1.5));
        cfg.setSlAtrMaxPct(BigDecimal.valueOf(3.5));
        // 0.5 * 1.5 = 0.75 → min 1.5로 floor
        assertEquals(1.5, cfg.computeDynamicSlPct(0.5, 2.5), 0.001);
    }

    @Test
    @DisplayName("MR computeDynamicSlPct + AD computeDynamicSlPct 동일 동작")
    public void mr_ad_dynamicSl_consistency() {
        MorningRushConfigEntity mr = new MorningRushConfigEntity();
        mr.setSlAtrEnabled(true);
        AllDayScannerConfigEntity ad = new AllDayScannerConfigEntity();
        ad.setSlAtrEnabled(true);
        // 동일 입력 → 동일 출력
        double mrSl = mr.computeDynamicSlPct(2.0, 2.5);
        double adSl = ad.computeDynamicSlPct(2.0, 2.5);
        assertEquals(mrSl, adSl, 0.001, "MR/AD 동작 일관성");
        // 2.0 * 1.5 = 3.0 (clamp 1.5~3.5 범위 안)
        assertEquals(3.0, mrSl, 0.001);
    }

    // ─────────────────────────────────────────────────────────────────
    //  3. AD entry window + HC 점수 setter 검증 (V132 마이그레이션 UPDATE)
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AD Entity: entry_start_hour 9 + min_confidence 9.0 setter 검증")
    public void ad_entryWindow_hcScoreUpdate() {
        AllDayScannerConfigEntity cfg = new AllDayScannerConfigEntity();
        // V132 마이그레이션 UPDATE 모방
        cfg.setEntryStartHour(9);
        cfg.setEntryStartMin(0);
        cfg.setMinConfidence(BigDecimal.valueOf(9.0));

        assertEquals(9, cfg.getEntryStartHour());
        assertEquals(0, cfg.getEntryStartMin());
        assertEquals(9.0, cfg.getMinConfidence().doubleValue(), 0.001);
    }
}
