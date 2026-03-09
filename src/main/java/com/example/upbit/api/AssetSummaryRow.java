package com.example.upbit.api;

import java.math.BigDecimal;

public class AssetSummaryRow {
    public String currency;
    public BigDecimal available;
    public BigDecimal locked;
    public BigDecimal avgBuyPrice;   // optional
    public String unitCurrency;
    public BigDecimal valuationKrw;  // optional
}
