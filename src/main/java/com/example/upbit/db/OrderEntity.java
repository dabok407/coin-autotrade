package com.example.upbit.db;

import javax.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name="order_log", indexes=@Index(name="idx_identifier", columnList="identifier", unique=true))
public class OrderEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name="identifier", nullable=false, length=64, unique=true)
    private String identifier;

    @Column(name="market", nullable=false, length=20)
    private String market;

    @Column(name="side", nullable=false, length=10)
    private String side;

    @Column(name="ord_type", nullable=false, length=20)
    private String ordType;

    @Column(name="price", precision=28, scale=12)
    private BigDecimal price;

    @Column(name="volume", precision=28, scale=12)
    private BigDecimal volume;

    @Column(name="state", length=20)
    private String state;

    @Column(name="executed_volume", precision=28, scale=12)
    private BigDecimal executedVolume;

    @Column(name="avg_price", precision=28, scale=12)
    private BigDecimal avgPrice;

    @Column(name="error_code", length=64)
    private String errorCode;

    @Column(name="error_message", length=255)
    private String errorMessage;

    @Column(name="uuid", length=64)
    private String uuid;

    @Column(name="ts_epoch_ms", nullable=false)
    private long tsEpochMs;

    public Long getId() { return id; }
    public String getIdentifier() { return identifier; }
    public void setIdentifier(String identifier) { this.identifier = identifier; }
    public String getMarket() { return market; }
    public void setMarket(String market) { this.market = market; }
    public String getSide() { return side; }
    public void setSide(String side) { this.side = side; }
    public String getOrdType() { return ordType; }
    public void setOrdType(String ordType) { this.ordType = ordType; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public BigDecimal getVolume() { return volume; }
    public void setVolume(BigDecimal volume) { this.volume = volume; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public BigDecimal getExecutedVolume() { return executedVolume; }
    public void setExecutedVolume(BigDecimal executedVolume) { this.executedVolume = executedVolume; }
    public BigDecimal getAvgPrice() { return avgPrice; }
    public void setAvgPrice(BigDecimal avgPrice) { this.avgPrice = avgPrice; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage != null && errorMessage.length() > 255
                ? errorMessage.substring(0, 252) + "..."
                : errorMessage;
    }
    public String getUuid() { return uuid; }
    public void setUuid(String uuid) { this.uuid = uuid; }
    public long getTsEpochMs() { return tsEpochMs; }
    public void setTsEpochMs(long tsEpochMs) { this.tsEpochMs = tsEpochMs; }
}
