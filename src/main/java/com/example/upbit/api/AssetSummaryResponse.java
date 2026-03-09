package com.example.upbit.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

public class AssetSummaryResponse {
    public BigDecimal availableKrw;
    public BigDecimal lockedKrw;
    public BigDecimal totalEquityKrw; // in minimal mode: KRW available+locked only
    public OffsetDateTime asOf;
    public List<AssetSummaryRow> assets = new ArrayList<>();
    public boolean configured; // whether API keys are configured
}
