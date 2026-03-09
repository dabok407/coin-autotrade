package com.example.upbit.dashboard;

import com.example.upbit.upbit.UpbitAccount;
import com.example.upbit.upbit.UpbitPrivateClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

@Service
public class AssetSummaryService {

    private final UpbitPrivateClient privateClient;

    public AssetSummaryService(UpbitPrivateClient privateClient) {
        this.privateClient = privateClient;
    }

    public AssetSummaryResponse getSummary() {
        AssetSummaryResponse res = new AssetSummaryResponse();
        res.asOf = OffsetDateTime.now(ZoneOffset.ofHours(9));

        // API key not configured -> return safely for UI
        if (privateClient == null || !privateClient.isConfigured()) {
            res.configured = false;
            res.message = "Upbit keys not configured.";
            res.availableKrw = BigDecimal.ZERO;
            res.lockedKrw = BigDecimal.ZERO;
            res.totalEquityKrw = BigDecimal.ZERO;
            res.assets = new ArrayList<AssetSummaryRow>();
            return res;
        }

        try {
            List<UpbitAccount> accounts = privateClient.getAccounts();
            res.configured = true;

            BigDecimal availableKrw = BigDecimal.ZERO;
            BigDecimal lockedKrw = BigDecimal.ZERO;

            List<AssetSummaryRow> rows = new ArrayList<AssetSummaryRow>();

            if (accounts != null) {
                for (UpbitAccount a : accounts) {
                    if (a == null) continue;

                    AssetSummaryRow r = new AssetSummaryRow();
                    r.currency = a.currency;
                    r.unitCurrency = a.unit_currency;
                    r.available = nz(a.balanceAsBigDecimal());
                    r.locked = nz(a.lockedAsBigDecimal());
                    r.avgBuyPrice = nz(a.avgBuyPriceAsBigDecimal());

                    if (a.currency != null && "KRW".equalsIgnoreCase(a.currency)) {
                        availableKrw = r.available;
                        lockedKrw = r.locked;
                    }

                    rows.add(r);
                }
            }

            res.availableKrw = availableKrw.setScale(0, RoundingMode.DOWN);
            res.lockedKrw = lockedKrw.setScale(0, RoundingMode.DOWN);
            res.totalEquityKrw = availableKrw.add(lockedKrw).setScale(0, RoundingMode.DOWN);
            res.assets = rows;
            return res;

        } catch (Exception e) {
            res.configured = true; // keys exist but call failed
            res.message = "Failed to fetch accounts: " + e.getMessage();
            res.availableKrw = BigDecimal.ZERO;
            res.lockedKrw = BigDecimal.ZERO;
            res.totalEquityKrw = BigDecimal.ZERO;
            res.assets = new ArrayList<AssetSummaryRow>();
            return res;
        }
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
