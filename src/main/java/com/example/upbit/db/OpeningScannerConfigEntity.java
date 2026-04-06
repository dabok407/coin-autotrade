package com.example.upbit.db;

import javax.persistence.*;
import java.math.BigDecimal;

/**
 * 오프닝 레인지 돌파 스캐너 설정 (단일 행, id=1).
 * 메인 봇(TradingBotService)과 독립적으로 운영되는 스캐너의 설정을 저장.
 */
@Entity
@Table(name = "opening_scanner_config")
public class OpeningScannerConfigEntity {

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
    private int maxPositions = 3;

    @Column(name = "capital_krw", nullable = false, precision = 20, scale = 2)
    private BigDecimal capitalKrw = BigDecimal.valueOf(100000);

    @Column(name = "order_sizing_mode", nullable = false, length = 10)
    private String orderSizingMode = "PCT";

    @Column(name = "order_sizing_value", nullable = false, precision = 10, scale = 2)
    private BigDecimal orderSizingValue = BigDecimal.valueOf(30);

    @Column(name = "candle_unit_min", nullable = false)
    private int candleUnitMin = 5;

    // ── 타이밍 파라미터 ──
    @Column(name = "range_start_hour", nullable = false)
    private int rangeStartHour = 8;

    @Column(name = "range_start_min", nullable = false)
    private int rangeStartMin = 0;

    @Column(name = "range_end_hour", nullable = false)
    private int rangeEndHour = 8;

    @Column(name = "range_end_min", nullable = false)
    private int rangeEndMin = 59;

    @Column(name = "entry_start_hour", nullable = false)
    private int entryStartHour = 9;

    @Column(name = "entry_start_min", nullable = false)
    private int entryStartMin = 0;  // V51: 09:05 → 09:00

    @Column(name = "entry_end_hour", nullable = false)
    private int entryEndHour = 10;

    @Column(name = "entry_end_min", nullable = false)
    private int entryEndMin = 25;  // V49: 10:30 → 10:25

    @Column(name = "session_end_hour", nullable = false)
    private int sessionEndHour = 12;

    @Column(name = "session_end_min", nullable = false)
    private int sessionEndMin = 0;

    // ── 리스크 파라미터 (v2: 스캘핑 최적화) ──
    @Column(name = "tp_atr_mult", nullable = false, precision = 5, scale = 2)
    private BigDecimal tpAtrMult = BigDecimal.valueOf(1.5);

    @Column(name = "sl_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal slPct = BigDecimal.valueOf(2.0);

    @Column(name = "trail_atr_mult", nullable = false, precision = 5, scale = 2)
    private BigDecimal trailAtrMult = BigDecimal.valueOf(0.6);

    // ── 필터 ──
    @Column(name = "btc_filter_enabled", nullable = false)
    private boolean btcFilterEnabled = true;

    @Column(name = "btc_ema_period", nullable = false)
    private int btcEmaPeriod = 20;

    @Column(name = "volume_mult", nullable = false, precision = 5, scale = 2)
    private BigDecimal volumeMult = BigDecimal.valueOf(1.5);

    @Column(name = "min_body_ratio", nullable = false, precision = 5, scale = 2)
    private BigDecimal minBodyRatio = BigDecimal.valueOf(0.45);

    @Column(name = "exclude_markets", length = 1000)
    private String excludeMarkets = "";

    @Column(name = "open_failed_enabled", nullable = false)
    private boolean openFailedEnabled = false;

    @Column(name = "min_price_krw", nullable = false)
    private int minPriceKrw = 20;

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

    /** @deprecated v36: Global Capital(bot_config.capital_krw)로 통합됨. 이 필드는 더 이상 사용하지 않음. */
    @Deprecated
    public BigDecimal getCapitalKrw() { return capitalKrw; }
    /** @deprecated v36: Global Capital(bot_config.capital_krw)로 통합됨. */
    @Deprecated
    public void setCapitalKrw(BigDecimal capitalKrw) { this.capitalKrw = capitalKrw != null ? capitalKrw : BigDecimal.valueOf(100000); }

    public String getOrderSizingMode() { return orderSizingMode; }
    public void setOrderSizingMode(String orderSizingMode) { this.orderSizingMode = orderSizingMode != null ? orderSizingMode.toUpperCase() : "PCT"; }

    public BigDecimal getOrderSizingValue() { return orderSizingValue; }
    public void setOrderSizingValue(BigDecimal orderSizingValue) { this.orderSizingValue = orderSizingValue != null ? orderSizingValue : BigDecimal.valueOf(30); }

    public int getCandleUnitMin() { return candleUnitMin; }
    public void setCandleUnitMin(int candleUnitMin) { this.candleUnitMin = candleUnitMin > 0 ? candleUnitMin : 5; }

    public int getRangeStartHour() { return rangeStartHour; }
    public void setRangeStartHour(int v) { this.rangeStartHour = v; }

    public int getRangeStartMin() { return rangeStartMin; }
    public void setRangeStartMin(int v) { this.rangeStartMin = v; }

    public int getRangeEndHour() { return rangeEndHour; }
    public void setRangeEndHour(int v) { this.rangeEndHour = v; }

    public int getRangeEndMin() { return rangeEndMin; }
    public void setRangeEndMin(int v) { this.rangeEndMin = v; }

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

    public BigDecimal getTpAtrMult() { return tpAtrMult; }
    public void setTpAtrMult(BigDecimal v) { this.tpAtrMult = v != null ? v : BigDecimal.valueOf(1.5); }

    public BigDecimal getSlPct() { return slPct; }
    public void setSlPct(BigDecimal v) { this.slPct = v != null ? v : BigDecimal.valueOf(2.0); }

    public BigDecimal getTrailAtrMult() { return trailAtrMult; }
    public void setTrailAtrMult(BigDecimal v) { this.trailAtrMult = v != null ? v : BigDecimal.valueOf(0.6); }

    public boolean isBtcFilterEnabled() { return btcFilterEnabled; }
    public void setBtcFilterEnabled(boolean btcFilterEnabled) { this.btcFilterEnabled = btcFilterEnabled; }

    public int getBtcEmaPeriod() { return btcEmaPeriod; }
    public void setBtcEmaPeriod(int btcEmaPeriod) { this.btcEmaPeriod = Math.max(5, btcEmaPeriod); }

    public BigDecimal getVolumeMult() { return volumeMult; }
    public void setVolumeMult(BigDecimal v) { this.volumeMult = v != null ? v : BigDecimal.valueOf(1.5); }

    public BigDecimal getMinBodyRatio() { return minBodyRatio; }
    public void setMinBodyRatio(BigDecimal v) { this.minBodyRatio = v != null ? v : BigDecimal.valueOf(0.45); }

    public boolean isOpenFailedEnabled() { return openFailedEnabled; }
    public void setOpenFailedEnabled(boolean openFailedEnabled) { this.openFailedEnabled = openFailedEnabled; }

    public int getMinPriceKrw() { return minPriceKrw; }
    public void setMinPriceKrw(int v) { this.minPriceKrw = v; }

    public String getExcludeMarkets() { return excludeMarkets != null ? excludeMarkets : ""; }
    public void setExcludeMarkets(String v) { this.excludeMarkets = v != null ? v.trim() : ""; }

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
