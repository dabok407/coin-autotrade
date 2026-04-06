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
