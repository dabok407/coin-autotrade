package com.example.upbit.db;

import javax.persistence.*;
import java.time.Instant;

/**
 * Persistent per-market runtime state for safe restart / catch-up.
 * - lastCandleUtc: latest processed candle close time (Upbit candle_date_time_utc)
 * - downStreak: example state used by consecutive-down strategy
 */
@Entity
@Table(name = "STRATEGY_STATE")
public class StrategyStateEntity {

    @Id
    @Column(name = "MARKET", length = 20, nullable = false)
    private String market;

    @Column(name = "LAST_CANDLE_UTC", length = 32)
    private String lastCandleUtc;

    @Column(name = "DOWN_STREAK")
    private Integer downStreak;

    @Column(name = "LAST_PRICE")
    private Double lastPrice;

    @Column(name = "UPDATED_AT_EPOCH_MS")
    private Long updatedAtEpochMs;

    public StrategyStateEntity() {}

    public StrategyStateEntity(String market) {
        this.market = market;
        this.updatedAtEpochMs = System.currentTimeMillis();
    }

    @PrePersist
    @PreUpdate
    public void touch() {
        this.updatedAtEpochMs = System.currentTimeMillis();
    }

    public String getMarket() { return market; }
    public void setMarket(String market) { this.market = market; }

    public String getLastCandleUtc() { return lastCandleUtc; }
    public void setLastCandleUtc(String lastCandleUtc) { this.lastCandleUtc = lastCandleUtc; }

    public Integer getDownStreak() { return downStreak; }
    public void setDownStreak(Integer downStreak) { this.downStreak = downStreak; }

    public Double getLastPrice() { return lastPrice; }
    public void setLastPrice(Double lastPrice) { this.lastPrice = lastPrice; }

    public Long getUpdatedAtEpochMs() { return updatedAtEpochMs; }
    public void setUpdatedAtEpochMs(Long updatedAtEpochMs) { this.updatedAtEpochMs = updatedAtEpochMs; }
}
