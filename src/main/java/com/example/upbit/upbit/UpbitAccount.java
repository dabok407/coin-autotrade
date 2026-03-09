package com.example.upbit.upbit;

import java.math.BigDecimal;

/**
 * Upbit /v1/accounts response DTO.
 *
 * Upbit returns numeric fields as strings. We keep them as String and parse safely in services.
 */
public class UpbitAccount {
    public String currency; // e.g. "KRW", "BTC"
    public String balance;  // string number
    public String locked;   // string number
    public String avg_buy_price; // string number
    public String avg_buy_price_modified; // "true"/"false"
    public String unit_currency; // usually "KRW"

    // Convenience safe parsers
    public BigDecimal balanceAsBigDecimal() { return toBd(balance); }
    public BigDecimal lockedAsBigDecimal() { return toBd(locked); }
    public BigDecimal avgBuyPriceAsBigDecimal() { return toBd(avg_buy_price); }

    private static BigDecimal toBd(String s) {
        if (s == null || s.trim().isEmpty()) return BigDecimal.ZERO;
        try { return new BigDecimal(s.trim()); } catch (Exception e) { return BigDecimal.ZERO; }
    }
}
