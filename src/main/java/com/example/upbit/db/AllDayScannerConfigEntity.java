package com.example.upbit.db;

import javax.persistence.*;
import java.math.BigDecimal;

/**
 * 종일 스캐너 설정 (단일 행, id=1).
 * 오프닝 레인지 스캐너와 독립적으로 운영되는 종일 스캐너의 설정을 저장.
 */
@Entity
@Table(name = "allday_scanner_config")
public class AllDayScannerConfigEntity {

    @Id
    @Column(name = "id")
    private int id = 1;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = false;

    @Column(name = "mode", nullable = false, length = 10)
    private String mode = "PAPER";

    @Column(name = "top_n", nullable = false)
    private int topN = 50;

    @Column(name = "max_positions", nullable = false)
    private int maxPositions = 2;

    @Column(name = "order_sizing_mode", nullable = false, length = 10)
    private String orderSizingMode = "PCT";

    @Column(name = "order_sizing_value", nullable = false, precision = 10, scale = 2)
    private BigDecimal orderSizingValue = BigDecimal.valueOf(20);

    @Column(name = "candle_unit_min", nullable = false)
    private int candleUnitMin = 5;

    // ── 타이밍 파라미터 ──
    @Column(name = "entry_start_hour", nullable = false)
    private int entryStartHour = 10;

    @Column(name = "entry_start_min", nullable = false)
    private int entryStartMin = 30;  // V105: 오프닝(~10:29) 직후 시작

    @Column(name = "entry_end_hour", nullable = false)
    private int entryEndHour = 22;

    @Column(name = "entry_end_min", nullable = false)
    private int entryEndMin = 0;

    @Column(name = "session_end_hour", nullable = false)
    private int sessionEndHour = 23;

    @Column(name = "session_end_min", nullable = false)
    private int sessionEndMin = 0;

    // ── 리스크 파라미터 ──
    @Column(name = "sl_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal slPct = BigDecimal.valueOf(1.5);

    @Column(name = "trail_atr_mult", nullable = false, precision = 5, scale = 2)
    private BigDecimal trailAtrMult = BigDecimal.valueOf(0.8);

    @Column(name = "min_confidence", nullable = false, precision = 5, scale = 2)
    private BigDecimal minConfidence = BigDecimal.valueOf(9.4);

    @Column(name = "time_stop_candles", nullable = false)
    private int timeStopCandles = 0;  // V55: 비활성화 (0 = OFF)

    @Column(name = "time_stop_min_pnl", nullable = false, precision = 5, scale = 2)
    private BigDecimal timeStopMinPnl = BigDecimal.valueOf(0.3);

    // ── 필터 ──
    @Column(name = "btc_filter_enabled", nullable = false)
    private boolean btcFilterEnabled = true;

    @Column(name = "btc_ema_period", nullable = false)
    private int btcEmaPeriod = 20;

    @Column(name = "volume_surge_mult", nullable = false, precision = 5, scale = 2)
    private BigDecimal volumeSurgeMult = BigDecimal.valueOf(3.0);

    @Column(name = "min_body_ratio", nullable = false, precision = 5, scale = 2)
    private BigDecimal minBodyRatio = BigDecimal.valueOf(0.60);

    @Column(name = "exclude_markets", length = 1000)
    private String excludeMarkets = "";

    @Column(name = "min_price_krw", nullable = false)
    private int minPriceKrw = 20;

    // ── Quick TP 파라미터 ──
    @Column(name = "quick_tp_enabled", nullable = false)
    private boolean quickTpEnabled = true;

    @Column(name = "quick_tp_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal quickTpPct = BigDecimal.valueOf(2.10);

    @Column(name = "quick_tp_interval_sec", nullable = false)
    private int quickTpIntervalSec = 5;

    // ── 신규 청산 파라미터 ──
    @Column(name = "trail_activate_pct", nullable = false)
    private double trailActivatePct = 0.5;

    @Column(name = "grace_period_candles", nullable = false)
    private int gracePeriodCandles = 0;

    @Column(name = "ema_exit_enabled", nullable = false)
    private boolean emaExitEnabled = false;  // V109: 실시간 티어드 SL로 대체

    @Column(name = "macd_exit_enabled", nullable = false)
    private boolean macdExitEnabled = false;  // V109: 실시간 티어드 SL로 대체

    // ── 실시간 티어드 SL (V109, 모닝러쉬 패턴) ──
    @Column(name = "grace_period_sec", nullable = false)
    private int gracePeriodSec = 30;  // Grace: SL 무시 구간 (초)

    @Column(name = "wide_period_min", nullable = false)
    private int widePeriodMin = 15;  // Wide SL 적용 구간 (분)

    @Column(name = "wide_sl_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal wideSlPct = BigDecimal.valueOf(3.0);  // Wide SL %

    // ── TP_TRAIL DB화 (V109, 하드코딩 제거) ──
    @Column(name = "tp_trail_activate_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal tpTrailActivatePct = BigDecimal.valueOf(2.0);  // 트레일 활성화 %

    @Column(name = "tp_trail_drop_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal tpTrailDropPct = BigDecimal.valueOf(1.0);  // 피크 대비 하락 %

    // ── 진입 필터 강화 (V109) ──
    @Column(name = "max_entry_rsi", nullable = false)
    private int maxEntryRsi = 80;  // RSI 과매수 차단

    @Column(name = "min_volume_mult", nullable = false, precision = 5, scale = 2)
    private BigDecimal minVolumeMult = BigDecimal.valueOf(5.0);  // 최소 거래량 배수

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

    // ========== Getters & Setters ==========

    public int getId() { return id; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode != null ? mode.toUpperCase() : "PAPER"; }

    public int getTopN() { return topN; }
    public void setTopN(int topN) { this.topN = Math.max(1, Math.min(100, topN)); }

    public int getMaxPositions() { return maxPositions; }
    public void setMaxPositions(int maxPositions) { this.maxPositions = Math.max(1, Math.min(15, maxPositions)); }

    public String getOrderSizingMode() { return orderSizingMode; }
    public void setOrderSizingMode(String orderSizingMode) { this.orderSizingMode = orderSizingMode != null ? orderSizingMode.toUpperCase() : "PCT"; }

    public BigDecimal getOrderSizingValue() { return orderSizingValue; }
    public void setOrderSizingValue(BigDecimal orderSizingValue) { this.orderSizingValue = orderSizingValue != null ? orderSizingValue : BigDecimal.valueOf(20); }

    public int getCandleUnitMin() { return candleUnitMin; }
    public void setCandleUnitMin(int candleUnitMin) { this.candleUnitMin = candleUnitMin > 0 ? candleUnitMin : 5; }

    public int getEntryStartHour() { return entryStartHour; }
    public void setEntryStartHour(int v) { this.entryStartHour = v; }

    public int getEntryStartMin() { return entryStartMin; }
    public void setEntryStartMin(int v) { this.entryStartMin = v; }

    public int getEntryEndHour() { return entryEndHour; }
    public void setEntryEndHour(int v) { this.entryEndHour = v; }

    public int getEntryEndMin() { return entryEndMin; }
    public void setEntryEndMin(int v) { this.entryEndMin = v; }

    public int getSessionEndHour() { return sessionEndHour; }
    public void setSessionEndHour(int v) { this.sessionEndHour = v; }

    public int getSessionEndMin() { return sessionEndMin; }
    public void setSessionEndMin(int v) { this.sessionEndMin = v; }

    public BigDecimal getSlPct() { return slPct; }
    public void setSlPct(BigDecimal v) { this.slPct = v != null ? v : BigDecimal.valueOf(1.5); }

    public BigDecimal getTrailAtrMult() { return trailAtrMult; }
    public void setTrailAtrMult(BigDecimal v) { this.trailAtrMult = v != null ? v : BigDecimal.valueOf(0.8); }

    public BigDecimal getMinConfidence() { return minConfidence; }
    public void setMinConfidence(BigDecimal v) { this.minConfidence = v != null ? v : BigDecimal.valueOf(9.4); }

    public int getTimeStopCandles() { return timeStopCandles; }
    public void setTimeStopCandles(int v) { this.timeStopCandles = Math.max(1, v); }

    public BigDecimal getTimeStopMinPnl() { return timeStopMinPnl; }
    public void setTimeStopMinPnl(BigDecimal v) { this.timeStopMinPnl = v != null ? v : BigDecimal.valueOf(0.3); }

    public boolean isBtcFilterEnabled() { return btcFilterEnabled; }
    public void setBtcFilterEnabled(boolean btcFilterEnabled) { this.btcFilterEnabled = btcFilterEnabled; }

    public int getBtcEmaPeriod() { return btcEmaPeriod; }
    public void setBtcEmaPeriod(int btcEmaPeriod) { this.btcEmaPeriod = Math.max(5, btcEmaPeriod); }

    public BigDecimal getVolumeSurgeMult() { return volumeSurgeMult; }
    public void setVolumeSurgeMult(BigDecimal v) { this.volumeSurgeMult = v != null ? v : BigDecimal.valueOf(3.0); }

    public BigDecimal getMinBodyRatio() { return minBodyRatio; }
    public void setMinBodyRatio(BigDecimal v) { this.minBodyRatio = v != null ? v : BigDecimal.valueOf(0.60); }

    public int getMinPriceKrw() { return minPriceKrw; }
    public void setMinPriceKrw(int v) { this.minPriceKrw = v; }

    public String getExcludeMarkets() { return excludeMarkets != null ? excludeMarkets : ""; }
    public void setExcludeMarkets(String v) { this.excludeMarkets = v != null ? v.trim() : ""; }

    public boolean isQuickTpEnabled() { return quickTpEnabled; }
    public void setQuickTpEnabled(boolean quickTpEnabled) { this.quickTpEnabled = quickTpEnabled; }

    public double getQuickTpPct() { return quickTpPct != null ? quickTpPct.doubleValue() : 2.10; }
    public BigDecimal getQuickTpPctBD() { return quickTpPct != null ? quickTpPct : BigDecimal.valueOf(2.10); }
    public void setQuickTpPct(BigDecimal v) { this.quickTpPct = v != null ? v : BigDecimal.valueOf(2.10); }

    public int getQuickTpIntervalSec() { return quickTpIntervalSec; }
    public void setQuickTpIntervalSec(int v) { this.quickTpIntervalSec = Math.max(3, Math.min(60, v)); }

    public double getTrailActivatePct() { return trailActivatePct; }
    public void setTrailActivatePct(double v) { this.trailActivatePct = Math.max(0, v); }

    public int getGracePeriodCandles() { return gracePeriodCandles; }
    public void setGracePeriodCandles(int v) { this.gracePeriodCandles = Math.max(0, v); }

    public boolean isEmaExitEnabled() { return emaExitEnabled; }
    public void setEmaExitEnabled(boolean v) { this.emaExitEnabled = v; }

    public boolean isMacdExitEnabled() { return macdExitEnabled; }
    public void setMacdExitEnabled(boolean v) { this.macdExitEnabled = v; }

    // ── 실시간 티어드 SL (V109) ──
    public int getGracePeriodSec() { return gracePeriodSec; }
    public void setGracePeriodSec(int v) { this.gracePeriodSec = Math.max(0, v); }

    public int getWidePeriodMin() { return widePeriodMin; }
    public void setWidePeriodMin(int v) { this.widePeriodMin = Math.max(1, v); }

    public BigDecimal getWideSlPct() { return wideSlPct; }
    public void setWideSlPct(BigDecimal v) { this.wideSlPct = v != null ? v : BigDecimal.valueOf(3.0); }

    public BigDecimal getTpTrailActivatePct() { return tpTrailActivatePct; }
    public void setTpTrailActivatePct(BigDecimal v) { this.tpTrailActivatePct = v != null ? v : BigDecimal.valueOf(2.0); }

    public BigDecimal getTpTrailDropPct() { return tpTrailDropPct; }
    public void setTpTrailDropPct(BigDecimal v) { this.tpTrailDropPct = v != null ? v : BigDecimal.valueOf(1.0); }

    public int getMaxEntryRsi() { return maxEntryRsi; }
    public void setMaxEntryRsi(int v) { this.maxEntryRsi = Math.max(50, Math.min(100, v)); }

    public BigDecimal getMinVolumeMult() { return minVolumeMult; }
    public void setMinVolumeMult(BigDecimal v) { this.minVolumeMult = v != null ? v : BigDecimal.valueOf(5.0); }

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

    /** 제외 마켓 목록을 Set으로 반환 (CSV 파싱) */
    public java.util.Set<String> getExcludeMarketsSet() {
        java.util.Set<String> set = new java.util.HashSet<String>();
        if (excludeMarkets == null || excludeMarkets.trim().isEmpty()) return set;
        for (String m : excludeMarkets.split(",")) {
            String trimmed = m.trim();
            if (!trimmed.isEmpty()) set.add(trimmed);
        }
        return set;
    }
}
