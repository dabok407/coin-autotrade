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
    private int topN = 15;

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
    private int entryStartMin = 35;

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
    private int timeStopCandles = 12;

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
    private BigDecimal quickTpPct = BigDecimal.valueOf(0.70);

    @Column(name = "quick_tp_interval_sec", nullable = false)
    private int quickTpIntervalSec = 5;

    // ========== Getters & Setters ==========

    public int getId() { return id; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode != null ? mode.toUpperCase() : "PAPER"; }

    public int getTopN() { return topN; }
    public void setTopN(int topN) { this.topN = Math.max(1, Math.min(50, topN)); }

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
    public void setMinPriceKrw(int v) { this.minPriceKrw = Math.max(0, v); }

    public String getExcludeMarkets() { return excludeMarkets != null ? excludeMarkets : ""; }
    public void setExcludeMarkets(String v) { this.excludeMarkets = v != null ? v.trim() : ""; }

    public boolean isQuickTpEnabled() { return quickTpEnabled; }
    public void setQuickTpEnabled(boolean quickTpEnabled) { this.quickTpEnabled = quickTpEnabled; }

    public double getQuickTpPct() { return quickTpPct != null ? quickTpPct.doubleValue() : 0.70; }
    public BigDecimal getQuickTpPctBD() { return quickTpPct != null ? quickTpPct : BigDecimal.valueOf(0.70); }
    public void setQuickTpPct(BigDecimal v) { this.quickTpPct = v != null ? v : BigDecimal.valueOf(0.70); }

    public int getQuickTpIntervalSec() { return quickTpIntervalSec; }
    public void setQuickTpIntervalSec(int v) { this.quickTpIntervalSec = Math.max(3, Math.min(60, v)); }

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
