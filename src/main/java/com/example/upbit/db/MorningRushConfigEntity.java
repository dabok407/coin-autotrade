package com.example.upbit.db;

import javax.persistence.*;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;

/**
 * 모닝 러쉬 스캐너 설정 (단일 행, id=1).
 * 09:00 KST 갭업 스파이크를 잡는 단기 스캐너.
 */
@Entity
@Table(name = "morning_rush_config")
public class MorningRushConfigEntity {

    @Id
    @Column(name = "id")
    private int id = 1;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = false;

    @Column(name = "mode", nullable = false, length = 10)
    private String mode = "PAPER";

    @Column(name = "top_n", nullable = false)
    private int topN = 30;

    @Column(name = "max_positions", nullable = false)
    private int maxPositions = 2;

    @Column(name = "order_sizing_mode", nullable = false, length = 10)
    private String orderSizingMode = "PCT";

    @Column(name = "order_sizing_value", nullable = false, precision = 10, scale = 2)
    private BigDecimal orderSizingValue = BigDecimal.valueOf(20);

    @Column(name = "gap_threshold_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal gapThresholdPct = BigDecimal.valueOf(5.0);

    @Column(name = "volume_mult", nullable = false, precision = 5, scale = 2)
    private BigDecimal volumeMult = BigDecimal.valueOf(5.0);

    @Column(name = "confirm_count", nullable = false)
    private int confirmCount = 3;

    @Column(name = "check_interval_sec", nullable = false)
    private int checkIntervalSec = 5;

    @Column(name = "tp_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal tpPct = BigDecimal.valueOf(2.0);

    @Column(name = "sl_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal slPct = BigDecimal.valueOf(3.0);

    @Column(name = "session_end_hour", nullable = false)
    private int sessionEndHour = 10;

    @Column(name = "session_end_min", nullable = false)
    private int sessionEndMin = 0;

    // ── 페이즈 타이밍 (V105: 하드코딩 → DB 관리) ──
    /** 레인지 수집 시작 시각 (KST). 기본 08:50 */
    @Column(name = "range_start_hour", nullable = false)
    private int rangeStartHour = 8;
    @Column(name = "range_start_min", nullable = false)
    private int rangeStartMin = 50;

    /** 진입 시작 시각 (KST). 기본 09:00 */
    @Column(name = "entry_start_hour", nullable = false)
    private int entryStartHour = 9;
    @Column(name = "entry_start_min", nullable = false)
    private int entryStartMin = 0;

    /** 진입 종료 시각 (KST, exclusive). 기본 09:05 */
    @Column(name = "entry_end_hour", nullable = false)
    private int entryEndHour = 9;
    @Column(name = "entry_end_min", nullable = false)
    private int entryEndMin = 5;

    @Column(name = "min_trade_amount", nullable = false)
    private long minTradeAmount = 1000000000L;

    @Column(name = "exclude_markets", length = 1000)
    private String excludeMarkets = "";

    @Column(name = "min_price_krw", nullable = false)
    private int minPriceKrw = 20;

    /** 캔들 중간 급등 감지 임계값 (%). 0이면 비활성화. */
    @Column(name = "surge_threshold_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal surgeThresholdPct = BigDecimal.valueOf(3.0);

    /** 급등 감지 윈도우 (초). 이 시간 내 surgeThresholdPct 이상 상승 시 진입. */
    @Column(name = "surge_window_sec", nullable = false)
    private int surgeWindowSec = 30;

    /** SL 종합안: 매수 후 그레이스 기간 (초). 이 기간 동안 SL 무시. */
    @Column(name = "grace_period_sec", nullable = false)
    private int gracePeriodSec = 60;

    /** SL 종합안: SL_WIDE 지속 시간 (분). 90일 백테스트 검증값 30분. */
    @Column(name = "wide_period_min", nullable = false)
    private int widePeriodMin = 30;

    /** SL 종합안: 흔들기 보호용 SL 값 (%). 90일 백테스트 평균 깊이 -5.94% → -6.0% 적용. */
    @Column(name = "wide_sl_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal wideSlPct = BigDecimal.valueOf(3.5);

    /** V110: TP_TRAIL 피크 대비 하락 매도 기준 (%). 하드코딩 제거. */
    @Column(name = "tp_trail_drop_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal tpTrailDropPct = BigDecimal.valueOf(1.5);

    // ── V111: Split-Exit 분할 매도 설정 ──
    @Column(name = "split_exit_enabled", nullable = false)
    private boolean splitExitEnabled = false;

    @Column(name = "split_tp_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal splitTpPct = BigDecimal.valueOf(1.5);

    @Column(name = "split_ratio", nullable = false, precision = 4, scale = 2)
    private BigDecimal splitRatio = BigDecimal.valueOf(0.50);

    @Column(name = "trail_drop_after_split", nullable = false, precision = 5, scale = 2)
    private BigDecimal trailDropAfterSplit = BigDecimal.valueOf(1.2);

    /** V115: Split-Exit 1차 매도 TRAIL drop %. split_tp_pct 도달 후 peak 대비 drop 시 1차 매도. */
    @Column(name = "split_1st_trail_drop", nullable = false, precision = 5, scale = 2)
    private BigDecimal split1stTrailDrop = BigDecimal.valueOf(0.5);

    /** V129: SPLIT_1ST 체결 후 SPLIT_2ND_TRAIL 매도 쿨다운(초). 0~600. 기본 60.
     *  쿨다운 중 SL은 허용(진짜 급락 방어). Grace와는 시간대 다름(매수 직후 vs 1차 매도 직후). */
    @Column(name = "split_1st_cooldown_sec", nullable = false)
    private int split1stCooldownSec = 60;

    // ── V130: Trail Ladder A — peak% 구간별 차등 drop (8 컬럼) ──
    /** @deprecated V129 단일값. trail_ladder_enabled=false 시 fallback용. 삭제 금지. */
    // split_1st_trail_drop / trail_drop_after_split 컬럼은 위에 이미 존재 (호환성 유지)

    /** peak < 2% 구간: 1차 SPLIT_1ST drop 기준 */
    @Column(name = "split_1st_drop_under_2", nullable = false, precision = 5, scale = 2)
    private BigDecimal split1stDropUnder2 = BigDecimal.valueOf(0.50);

    /** peak 2~3% 구간: 1차 SPLIT_1ST drop 기준 */
    @Column(name = "split_1st_drop_under_3", nullable = false, precision = 5, scale = 2)
    private BigDecimal split1stDropUnder3 = BigDecimal.valueOf(1.00);

    /** peak 3~5% 구간: 1차 SPLIT_1ST drop 기준 */
    @Column(name = "split_1st_drop_under_5", nullable = false, precision = 5, scale = 2)
    private BigDecimal split1stDropUnder5 = BigDecimal.valueOf(1.50);

    /** peak >= 5% 구간: 1차 SPLIT_1ST drop 기준 */
    @Column(name = "split_1st_drop_above_5", nullable = false, precision = 5, scale = 2)
    private BigDecimal split1stDropAbove5 = BigDecimal.valueOf(2.00);

    /** peak < 2% 구간: 2차 trail after split drop 기준 */
    @Column(name = "trail_after_drop_under_2", nullable = false, precision = 5, scale = 2)
    private BigDecimal trailAfterDropUnder2 = BigDecimal.valueOf(1.00);

    /** peak 2~3% 구간: 2차 trail after split drop 기준 */
    @Column(name = "trail_after_drop_under_3", nullable = false, precision = 5, scale = 2)
    private BigDecimal trailAfterDropUnder3 = BigDecimal.valueOf(1.20);

    /** peak 3~5% 구간: 2차 trail after split drop 기준 */
    @Column(name = "trail_after_drop_under_5", nullable = false, precision = 5, scale = 2)
    private BigDecimal trailAfterDropUnder5 = BigDecimal.valueOf(1.50);

    /** peak >= 5% 구간: 2차 trail after split drop 기준 */
    @Column(name = "trail_after_drop_above_5", nullable = false, precision = 5, scale = 2)
    private BigDecimal trailAfterDropAbove5 = BigDecimal.valueOf(2.00);

    /** V130 ①: Trail Ladder 활성화. false=기존 단일값(split1stTrailDrop/trailDropAfterSplit) fallback */
    @Column(name = "trail_ladder_enabled", nullable = false)
    private boolean trailLadderEnabled = true;

    // ── V130 ② L1 60s 지연 진입 ──
    /** 시그널 후 N초 대기 후 현재가 >= 시그널가 확인 시 매수. 0=즉시(V129 동작). */
    @Column(name = "l1_delay_sec", nullable = false)
    private int l1DelaySec = 60;

    // ── V130 ④ SPLIT_1ST roi 하한선 ──
    /** SPLIT_1ST 발동 시 current_roi >= 이 값이어야 매도. 0.0=비활성(V129 동작). */
    @Column(name = "split_1st_roi_floor_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal split1stRoiFloorPct = BigDecimal.valueOf(0.30);

    // ── V131 Phase 1: L1 강제 익절 캡 ──
    // Entity default 0=비활성(V130 호환). V131 마이그레이션 UPDATE로 운영값 2.0 설정.
    /** armed 후 ROI 도달 시 강제 split1 매도. 0.0=비활성. 사용자 첫 의견(+2.0% 권고). */
    @Column(name = "l1_cap_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal l1CapPct = BigDecimal.valueOf(0.0);

    // ── V132 Phase 2: 동적 ATR SL ──
    @Column(name = "sl_atr_enabled", nullable = false)
    private boolean slAtrEnabled = false;

    @Column(name = "sl_atr_mult", nullable = false, precision = 4, scale = 2)
    private BigDecimal slAtrMult = BigDecimal.valueOf(1.5);

    @Column(name = "sl_atr_min_pct", nullable = false, precision = 4, scale = 2)
    private BigDecimal slAtrMinPct = BigDecimal.valueOf(1.5);

    @Column(name = "sl_atr_max_pct", nullable = false, precision = 4, scale = 2)
    private BigDecimal slAtrMaxPct = BigDecimal.valueOf(3.5);

    // ── V133 Phase 3: BEV 보장 ──
    @Column(name = "bev_guard_enabled", nullable = false)
    private boolean bevGuardEnabled = false;

    @Column(name = "bev_trigger_pct", nullable = false, precision = 4, scale = 2)
    private BigDecimal bevTriggerPct = BigDecimal.valueOf(5.0);

    // ========== Getters & Setters ==========

    public int getId() { return id; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode != null ? mode.toUpperCase() : "PAPER"; }

    public int getTopN() { return topN; }
    public void setTopN(int topN) { this.topN = Math.max(1, Math.min(100, topN)); }

    public int getMaxPositions() { return maxPositions; }
    public void setMaxPositions(int maxPositions) { this.maxPositions = Math.max(1, Math.min(10, maxPositions)); }

    public String getOrderSizingMode() { return orderSizingMode; }
    public void setOrderSizingMode(String v) { this.orderSizingMode = v != null ? v.toUpperCase() : "PCT"; }

    public BigDecimal getOrderSizingValue() { return orderSizingValue; }
    public void setOrderSizingValue(BigDecimal v) { this.orderSizingValue = v != null ? v : BigDecimal.valueOf(20); }

    public BigDecimal getGapThresholdPct() { return gapThresholdPct; }
    public void setGapThresholdPct(BigDecimal v) { this.gapThresholdPct = v != null ? v : BigDecimal.valueOf(5.0); }

    public BigDecimal getVolumeMult() { return volumeMult; }
    public void setVolumeMult(BigDecimal v) { this.volumeMult = v != null ? v : BigDecimal.valueOf(5.0); }

    public int getConfirmCount() { return confirmCount; }
    public void setConfirmCount(int v) { this.confirmCount = Math.max(1, Math.min(10, v)); }

    public int getCheckIntervalSec() { return checkIntervalSec; }
    public void setCheckIntervalSec(int v) { this.checkIntervalSec = Math.max(3, Math.min(30, v)); }

    public BigDecimal getTpPct() { return tpPct; }
    public void setTpPct(BigDecimal v) { this.tpPct = v != null ? v : BigDecimal.valueOf(2.0); }

    public BigDecimal getSlPct() { return slPct; }
    public void setSlPct(BigDecimal v) { this.slPct = v != null ? v : BigDecimal.valueOf(3.0); }

    public int getSessionEndHour() { return sessionEndHour; }
    public void setSessionEndHour(int v) { this.sessionEndHour = v; }

    public int getSessionEndMin() { return sessionEndMin; }
    public void setSessionEndMin(int v) { this.sessionEndMin = v; }

    public int getRangeStartHour() { return rangeStartHour; }
    public void setRangeStartHour(int v) { this.rangeStartHour = Math.max(0, Math.min(23, v)); }
    public int getRangeStartMin() { return rangeStartMin; }
    public void setRangeStartMin(int v) { this.rangeStartMin = Math.max(0, Math.min(59, v)); }

    public int getEntryStartHour() { return entryStartHour; }
    public void setEntryStartHour(int v) { this.entryStartHour = Math.max(0, Math.min(23, v)); }
    public int getEntryStartMin() { return entryStartMin; }
    public void setEntryStartMin(int v) { this.entryStartMin = Math.max(0, Math.min(59, v)); }

    public int getEntryEndHour() { return entryEndHour; }
    public void setEntryEndHour(int v) { this.entryEndHour = Math.max(0, Math.min(23, v)); }
    public int getEntryEndMin() { return entryEndMin; }
    public void setEntryEndMin(int v) { this.entryEndMin = Math.max(0, Math.min(59, v)); }

    public long getMinTradeAmount() { return minTradeAmount; }
    public void setMinTradeAmount(long v) { this.minTradeAmount = Math.max(0, v); }

    public String getExcludeMarkets() { return excludeMarkets != null ? excludeMarkets : ""; }
    public void setExcludeMarkets(String v) { this.excludeMarkets = v != null ? v.trim() : ""; }

    public int getMinPriceKrw() { return minPriceKrw; }
    public void setMinPriceKrw(int v) { this.minPriceKrw = v; }

    public BigDecimal getSurgeThresholdPct() { return surgeThresholdPct; }
    public void setSurgeThresholdPct(BigDecimal v) { this.surgeThresholdPct = v != null ? v : BigDecimal.valueOf(3.0); }

    public int getSurgeWindowSec() { return surgeWindowSec; }
    public void setSurgeWindowSec(int v) { this.surgeWindowSec = Math.max(5, Math.min(120, v)); }

    public int getGracePeriodSec() { return gracePeriodSec; }
    public void setGracePeriodSec(int v) { this.gracePeriodSec = Math.max(0, Math.min(600, v)); }

    public int getWidePeriodMin() { return widePeriodMin; }
    public void setWidePeriodMin(int v) { this.widePeriodMin = Math.max(1, Math.min(60, v)); }

    public BigDecimal getWideSlPct() { return wideSlPct; }
    public void setWideSlPct(BigDecimal v) { this.wideSlPct = v != null ? v : BigDecimal.valueOf(6.0); }

    public BigDecimal getTpTrailDropPct() { return tpTrailDropPct; }
    public void setTpTrailDropPct(BigDecimal v) { this.tpTrailDropPct = v != null ? v : BigDecimal.valueOf(1.5); }

    public boolean isSplitExitEnabled() { return splitExitEnabled; }
    public void setSplitExitEnabled(boolean v) { this.splitExitEnabled = v; }

    public BigDecimal getSplitTpPct() { return splitTpPct; }
    public void setSplitTpPct(BigDecimal v) { this.splitTpPct = v != null ? v : BigDecimal.valueOf(1.5); }

    public BigDecimal getSplitRatio() { return splitRatio; }
    public void setSplitRatio(BigDecimal v) { this.splitRatio = v != null ? v : BigDecimal.valueOf(0.60); }

    public BigDecimal getTrailDropAfterSplit() { return trailDropAfterSplit; }
    public void setTrailDropAfterSplit(BigDecimal v) { this.trailDropAfterSplit = v != null ? v : BigDecimal.valueOf(1.2); }

    public BigDecimal getSplit1stTrailDrop() { return split1stTrailDrop; }
    public void setSplit1stTrailDrop(BigDecimal v) { this.split1stTrailDrop = v != null ? v : BigDecimal.valueOf(0.5); }

    public int getSplit1stCooldownSec() { return split1stCooldownSec; }
    public void setSplit1stCooldownSec(int v) { this.split1stCooldownSec = Math.max(0, Math.min(600, v)); }

    // ── V130: Trail Ladder A ──
    public BigDecimal getSplit1stDropUnder2() { return split1stDropUnder2; }
    public void setSplit1stDropUnder2(BigDecimal v) { this.split1stDropUnder2 = v != null ? v : BigDecimal.valueOf(0.50); }

    public BigDecimal getSplit1stDropUnder3() { return split1stDropUnder3; }
    public void setSplit1stDropUnder3(BigDecimal v) { this.split1stDropUnder3 = v != null ? v : BigDecimal.valueOf(1.00); }

    public BigDecimal getSplit1stDropUnder5() { return split1stDropUnder5; }
    public void setSplit1stDropUnder5(BigDecimal v) { this.split1stDropUnder5 = v != null ? v : BigDecimal.valueOf(1.50); }

    public BigDecimal getSplit1stDropAbove5() { return split1stDropAbove5; }
    public void setSplit1stDropAbove5(BigDecimal v) { this.split1stDropAbove5 = v != null ? v : BigDecimal.valueOf(2.00); }

    public BigDecimal getTrailAfterDropUnder2() { return trailAfterDropUnder2; }
    public void setTrailAfterDropUnder2(BigDecimal v) { this.trailAfterDropUnder2 = v != null ? v : BigDecimal.valueOf(1.00); }

    public BigDecimal getTrailAfterDropUnder3() { return trailAfterDropUnder3; }
    public void setTrailAfterDropUnder3(BigDecimal v) { this.trailAfterDropUnder3 = v != null ? v : BigDecimal.valueOf(1.20); }

    public BigDecimal getTrailAfterDropUnder5() { return trailAfterDropUnder5; }
    public void setTrailAfterDropUnder5(BigDecimal v) { this.trailAfterDropUnder5 = v != null ? v : BigDecimal.valueOf(1.50); }

    public BigDecimal getTrailAfterDropAbove5() { return trailAfterDropAbove5; }
    public void setTrailAfterDropAbove5(BigDecimal v) { this.trailAfterDropAbove5 = v != null ? v : BigDecimal.valueOf(2.00); }

    public boolean isTrailLadderEnabled() { return trailLadderEnabled; }
    public void setTrailLadderEnabled(boolean v) { this.trailLadderEnabled = v; }

    public int getL1DelaySec() { return l1DelaySec; }
    public void setL1DelaySec(int v) { this.l1DelaySec = Math.max(0, Math.min(300, v)); }

    public BigDecimal getSplit1stRoiFloorPct() { return split1stRoiFloorPct; }
    public void setSplit1stRoiFloorPct(BigDecimal v) { this.split1stRoiFloorPct = v != null ? v : BigDecimal.ZERO; }

    public BigDecimal getL1CapPct() { return l1CapPct; }
    public void setL1CapPct(BigDecimal v) { this.l1CapPct = v != null ? v : BigDecimal.ZERO; }

    // ── V132 Phase 2: ATR SL ──
    public boolean isSlAtrEnabled() { return slAtrEnabled; }
    public void setSlAtrEnabled(boolean v) { this.slAtrEnabled = v; }
    public BigDecimal getSlAtrMult() { return slAtrMult; }
    public void setSlAtrMult(BigDecimal v) { this.slAtrMult = v != null ? v : BigDecimal.valueOf(1.5); }
    public BigDecimal getSlAtrMinPct() { return slAtrMinPct; }
    public void setSlAtrMinPct(BigDecimal v) { this.slAtrMinPct = v != null ? v : BigDecimal.valueOf(1.5); }
    public BigDecimal getSlAtrMaxPct() { return slAtrMaxPct; }
    public void setSlAtrMaxPct(BigDecimal v) { this.slAtrMaxPct = v != null ? v : BigDecimal.valueOf(3.5); }

    // ── V133 Phase 3: BEV 보장 ──
    public boolean isBevGuardEnabled() { return bevGuardEnabled; }
    public void setBevGuardEnabled(boolean v) { this.bevGuardEnabled = v; }
    public BigDecimal getBevTriggerPct() { return bevTriggerPct; }
    public void setBevTriggerPct(BigDecimal v) { this.bevTriggerPct = v != null ? v : BigDecimal.valueOf(5.0); }

    public double computeDynamicSlPct(double atrPct, double fallbackSlPct) {
        if (!slAtrEnabled || atrPct <= 0) return fallbackSlPct;
        double mult = slAtrMult.doubleValue();
        double min = slAtrMinPct.doubleValue();
        double max = slAtrMaxPct.doubleValue();
        double v = atrPct * mult;
        if (v < min) v = min;
        if (v > max) v = max;
        return v;
    }

    /**
     * V130 ①: peak% 구간에 따라 적용할 drop 임계값을 반환.
     * trail_ladder_enabled=false이면 기존 단일값 fallback (호환성).
     *
     * @param peakPct  진입가 대비 peak 상승률(%)
     * @param isAfterSplit  true=SPLIT_2ND_TRAIL용 trailAfterDrop, false=SPLIT_1ST용 split1stDrop
     */
    public BigDecimal getDropForPeak(double peakPct, boolean isAfterSplit) {
        if (!trailLadderEnabled) {
            return isAfterSplit ? trailDropAfterSplit : split1stTrailDrop;
        }
        if (isAfterSplit) {
            if (peakPct < 2) return trailAfterDropUnder2;
            if (peakPct < 3) return trailAfterDropUnder3;
            if (peakPct < 5) return trailAfterDropUnder5;
            return trailAfterDropAbove5;
        } else {
            if (peakPct < 2) return split1stDropUnder2;
            if (peakPct < 3) return split1stDropUnder3;
            if (peakPct < 5) return split1stDropUnder5;
            return split1stDropAbove5;
        }
    }

    /** 제외 마켓 목록을 Set으로 반환 (CSV 파싱) */
    public Set<String> getExcludeMarketsSet() {
        Set<String> set = new HashSet<String>();
        if (excludeMarkets == null || excludeMarkets.trim().isEmpty()) return set;
        for (String m : excludeMarkets.split(",")) {
            String trimmed = m.trim();
            if (!trimmed.isEmpty()) set.add(trimmed);
        }
        return set;
    }
}
