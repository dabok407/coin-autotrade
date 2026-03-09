package com.example.upbit.dashboard;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public class AssetSummaryResponse {
    public boolean configured;
    public String message;

    public BigDecimal availableKrw;
    public BigDecimal lockedKrw;
    public BigDecimal totalEquityKrw;

    public OffsetDateTime asOf;
    public List<AssetSummaryRow> assets;
}
