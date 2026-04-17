package com.example.upbit.db;

import javax.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 재시작/비정상 종료 대비를 위해 "현재 포지션"을 DB에 저장합니다.
 * LIVE 모드에서는 업비트 주문/체결과 동기화가 핵심이라, 이 테이블이 기준 상태(source of truth)가 됩니다.
 */
@Entity
@Table(name = "position")
public class PositionEntity {

    @Id
    @Column(name = "market", length = 20)
    private String market;

    /**
     * 수량/가격/원화 등 "정밀 값"은 double을 쓰지 않습니다.
     * (LIVE 체결/슬리피지/수수료 누적 시 double 오차가 쉽게 누적됩니다.)
     */
    @Column(name = "qty", nullable = false, precision = 28, scale = 18)
    private BigDecimal qty = BigDecimal.ZERO;

    @Column(name = "avg_price", nullable = false, precision = 28, scale = 8)
    private BigDecimal avgPrice = BigDecimal.ZERO;

    @Column(name = "add_buys", nullable = false)
    private int addBuys;

    @Column(name = "opened_at")
    private Instant openedAt;

    /** 진입(매수) 전략 이름. Strategy Lock 활성 시 이 전략만 매도 가능 */
    @Column(name = "entry_strategy", length = 100)
    private String entryStrategy;

    /** V111: Split-Exit 분할 매도 상태. 0=초기, 1=1차 완료(40% 잔량), -1=분할 불가 */
    @Column(name = "split_phase", nullable = false)
    private int splitPhase = 0;

    /** V111: Split-Exit 1차 매도 전 원래 수량 (자본 계산용) */
    @Column(name = "split_original_qty", precision = 28, scale = 18)
    private BigDecimal splitOriginalQty;

    /** V118: TRAIL peak 가격 (영속화). null=미관측. 재시작 시 실제 peak 복원용 */
    @Column(name = "peak_price", precision = 28, scale = 8)
    private BigDecimal peakPrice;

    /** V118: 1차 TRAIL armed 시점. null=미armed. 재시작 시 armed 상태 복원용 */
    @Column(name = "armed_at")
    private Instant armedAt;

    public String getMarket() { return market; }
    public void setMarket(String market) { this.market = market; }

    public BigDecimal getQty() { return qty; }
    public void setQty(BigDecimal qty) { this.qty = (qty == null ? BigDecimal.ZERO : qty); }
    public void setQty(double qty) { this.qty = BigDecimal.valueOf(qty); }

    public BigDecimal getAvgPrice() { return avgPrice; }
    public void setAvgPrice(BigDecimal avgPrice) { this.avgPrice = (avgPrice == null ? BigDecimal.ZERO : avgPrice); }
    public void setAvgPrice(double avgPrice) { this.avgPrice = BigDecimal.valueOf(avgPrice); }

    public int getAddBuys() { return addBuys; }
    public void setAddBuys(int addBuys) { this.addBuys = addBuys; }

    public Instant getOpenedAt() { return openedAt; }
    public void setOpenedAt(Instant openedAt) { this.openedAt = openedAt; }

    public String getEntryStrategy() { return entryStrategy; }
    public void setEntryStrategy(String entryStrategy) { this.entryStrategy = entryStrategy; }

    public int getSplitPhase() { return splitPhase; }
    public void setSplitPhase(int splitPhase) { this.splitPhase = splitPhase; }

    public BigDecimal getSplitOriginalQty() { return splitOriginalQty; }
    public void setSplitOriginalQty(BigDecimal splitOriginalQty) { this.splitOriginalQty = splitOriginalQty; }

    public BigDecimal getPeakPrice() { return peakPrice; }
    public void setPeakPrice(BigDecimal peakPrice) { this.peakPrice = peakPrice; }

    public Instant getArmedAt() { return armedAt; }
    public void setArmedAt(Instant armedAt) { this.armedAt = armedAt; }
}
