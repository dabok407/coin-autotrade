package com.example.upbit.db;

import javax.persistence.*;

@Entity
@Table(name = "candle_cache",
       uniqueConstraints = @UniqueConstraint(columnNames = {"market", "interval_min", "candle_ts_utc"}))
public class CandleCacheEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "market", nullable = false, length = 20)
    private String market;

    @Column(name = "interval_min", nullable = false)
    private int intervalMin;

    @Column(name = "candle_ts_utc", nullable = false, length = 30)
    private String candleTsUtc;

    @Column(name = "open_price", nullable = false)
    private double openPrice;

    @Column(name = "high_price", nullable = false)
    private double highPrice;

    @Column(name = "low_price", nullable = false)
    private double lowPrice;

    @Column(name = "close_price", nullable = false)
    private double closePrice;

    @Column(name = "volume", nullable = false)
    private double volume;

    public CandleCacheEntity() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getMarket() { return market; }
    public void setMarket(String market) { this.market = market; }

    public int getIntervalMin() { return intervalMin; }
    public void setIntervalMin(int intervalMin) { this.intervalMin = intervalMin; }

    public String getCandleTsUtc() { return candleTsUtc; }
    public void setCandleTsUtc(String candleTsUtc) { this.candleTsUtc = candleTsUtc; }

    public double getOpenPrice() { return openPrice; }
    public void setOpenPrice(double openPrice) { this.openPrice = openPrice; }

    public double getHighPrice() { return highPrice; }
    public void setHighPrice(double highPrice) { this.highPrice = highPrice; }

    public double getLowPrice() { return lowPrice; }
    public void setLowPrice(double lowPrice) { this.lowPrice = lowPrice; }

    public double getClosePrice() { return closePrice; }
    public void setClosePrice(double closePrice) { this.closePrice = closePrice; }

    public double getVolume() { return volume; }
    public void setVolume(double volume) { this.volume = volume; }
}
