package com.example.upbit.db;

import javax.persistence.*;
import java.math.BigDecimal;

/**
 * 체결/가상체결 로그 (trade_log)
 * - 대시보드 하단 "거래 로그" 및 백테스트 결과에 사용
 */
@Entity
@Table(name = "trade_log")
public class TradeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ts_epoch_ms", nullable = false)
    private long tsEpochMs;

    @Column(name = "market", nullable = false, length = 20)
    private String market;

    @Column(name = "action", nullable = false, length = 20)
    private String action;

    @Column(name = "price", nullable = false, precision=28, scale=12)
    private BigDecimal price;

    @Column(name = "qty", nullable = false, precision=28, scale=12)
    private BigDecimal qty;

    @Column(name = "pnl_krw", nullable = false, precision=20, scale=2)
    private BigDecimal pnlKrw;

    @Column(name = "roi_percent", nullable = false, precision=16, scale=6)
    private BigDecimal roiPercent;

    @Column(name = "mode", nullable = false, length = 10)
    private String mode;

    @Column(name = "note", length = 512)
    private String note;

    // V2__add_pattern_columns.sql
    @Column(name = "pattern_type", length = 64)
    private String patternType;

    @Column(name = "pattern_reason", length = 512)
    private String patternReason;

    /** 매도 시점의 평균 매수가 (매수건에서는 null) */
    @Column(name = "avg_buy_price", precision = 28, scale = 12)
    private BigDecimal avgBuyPrice;

    /** 패턴 신뢰도 점수 (1~10) */
    @Column(name = "confidence")
    private Double confidence;

    /** 거래 실행 시 사용된 분봉 단위 (5, 15, 30, 60, 240 등) — 차트 팝업용 */
    @Column(name = "candle_unit_min")
    private Integer candleUnitMin;

    // V128: 매도 row에 매수 이후 최고 peak/ROI/armed 기록, 매수 row에 entry_signal 기록
    @Column(name = "peak_price", precision = 28, scale = 12)
    private BigDecimal peakPrice;

    @Column(name = "peak_roi_pct", precision = 12, scale = 4)
    private BigDecimal peakRoiPct;

    @Column(name = "armed_flag", length = 8)
    private String armedFlag;

    @Column(name = "entry_signal", length = 256)
    private String entrySignal;

    public Long getId() { return id; }

    public long getTsEpochMs() { return tsEpochMs; }
    public void setTsEpochMs(long tsEpochMs) { this.tsEpochMs = tsEpochMs; }

    public String getMarket() { return market; }
    public void setMarket(String market) { this.market = market; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }

    /** Convenience overload for call sites that still use primitive doubles. */
    public void setPrice(double price) { this.price = BigDecimal.valueOf(price); }

    public BigDecimal getQty() { return qty; }
    public void setQty(BigDecimal qty) { this.qty = qty; }

    /** Convenience overload for call sites that still use primitive doubles. */
    public void setQty(double qty) { this.qty = BigDecimal.valueOf(qty); }

    public BigDecimal getPnlKrw() { return pnlKrw; }
    public void setPnlKrw(BigDecimal pnlKrw) { this.pnlKrw = pnlKrw; }

    /** Convenience overload for call sites that still use primitive doubles. */
    public void setPnlKrw(double pnlKrw) { this.pnlKrw = BigDecimal.valueOf(pnlKrw); }

    public BigDecimal getRoiPercent() { return roiPercent; }
    public void setRoiPercent(BigDecimal roiPercent) { this.roiPercent = roiPercent; }

    /** Convenience overload for call sites that still use primitive doubles. */
    public void setRoiPercent(double roiPercent) { this.roiPercent = BigDecimal.valueOf(roiPercent); }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    public String getPatternType() { return patternType; }
    public void setPatternType(String patternType) { this.patternType = patternType; }

    public String getPatternReason() { return patternReason; }
    public void setPatternReason(String patternReason) { this.patternReason = patternReason; }

    public BigDecimal getAvgBuyPrice() { return avgBuyPrice; }
    public void setAvgBuyPrice(BigDecimal avgBuyPrice) { this.avgBuyPrice = avgBuyPrice; }
    public void setAvgBuyPrice(double avgBuyPrice) { this.avgBuyPrice = BigDecimal.valueOf(avgBuyPrice); }

    public Double getConfidence() { return confidence; }
    public void setConfidence(Double confidence) { this.confidence = confidence; }

    public Integer getCandleUnitMin() { return candleUnitMin; }
    public void setCandleUnitMin(Integer candleUnitMin) { this.candleUnitMin = candleUnitMin; }

    public BigDecimal getPeakPrice() { return peakPrice; }
    public void setPeakPrice(BigDecimal peakPrice) { this.peakPrice = peakPrice; }
    public void setPeakPrice(double peakPrice) { this.peakPrice = BigDecimal.valueOf(peakPrice); }

    public BigDecimal getPeakRoiPct() { return peakRoiPct; }
    public void setPeakRoiPct(BigDecimal peakRoiPct) { this.peakRoiPct = peakRoiPct; }
    public void setPeakRoiPct(double peakRoiPct) { this.peakRoiPct = BigDecimal.valueOf(peakRoiPct); }

    public String getArmedFlag() { return armedFlag; }
    public void setArmedFlag(String armedFlag) { this.armedFlag = armedFlag; }

    public String getEntrySignal() { return entrySignal; }
    public void setEntrySignal(String entrySignal) { this.entrySignal = entrySignal; }
}
