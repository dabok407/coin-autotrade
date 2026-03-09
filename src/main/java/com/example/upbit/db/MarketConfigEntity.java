package com.example.upbit.db;

import javax.persistence.*;
import org.hibernate.annotations.Type;
import java.math.BigDecimal;

@Entity
@Table(name="market_config", uniqueConstraints=@UniqueConstraint(name="uk_market", columnNames="market"))
public class MarketConfigEntity {

    @Transient
    private String displayName;

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name="market", nullable=false, length=20)
    private String market;

    @Column(name="enabled", nullable=false, columnDefinition = "TINYINT(1)")
    @Type(type = "org.hibernate.type.NumericBooleanType")
    private Boolean enabled = true;

    // Money: keep as DECIMAL in DB and BigDecimal in JPA.
    @Column(name="base_order_krw", nullable=false, precision = 19, scale = 4)
    private BigDecimal baseOrderKrw = BigDecimal.valueOf(10000);

    public Long getId() { return id; }

    public String getMarket() { return market; }
    public void setMarket(String market) { this.market = market; }

    public boolean isEnabled() { return Boolean.TRUE.equals(enabled); }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public BigDecimal getBaseOrderKrw() { return baseOrderKrw; }
    public void setBaseOrderKrw(BigDecimal baseOrderKrw) {
        this.baseOrderKrw = (baseOrderKrw == null ? BigDecimal.ZERO : baseOrderKrw);
    }

    // Convenience for older call sites
    public void setBaseOrderKrw(double baseOrderKrw) { this.baseOrderKrw = BigDecimal.valueOf(baseOrderKrw); }
}
