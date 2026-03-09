package com.example.upbit.market;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Upbit public market catalog cache.
 * Uses https://api.upbit.com/v1/market/all to map market code -> korean name.
 */
@Service
public class UpbitMarketCatalogService {

    private static final String URL = "https://api.upbit.com/v1/market/all?isDetails=false";
    private static final Duration TTL = Duration.ofHours(6);

    private final RestTemplate restTemplate;

    private volatile Instant loadedAt = Instant.EPOCH;
    private final Map<String, MarketName> cache = new ConcurrentHashMap<String, MarketName>();

    public UpbitMarketCatalogService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public Map<String, MarketName> getCatalog() {
        refreshIfNeeded();
        return cache;
    }

    /**
     * 현재 캐시된 전체 마켓 코드 목록(Set).
     * - 예: ["KRW-BTC", "KRW-ETH", ...]
     */
    public Set<String> getAllMarketCodes() {
        refreshIfNeeded();
        return new HashSet<String>(cache.keySet());
    }

public String displayLabel(String marketCode) {
        if (marketCode == null) return null;
        refreshIfNeeded();
        MarketName n = cache.get(marketCode);
        if (n != null && n.koreanName != null && !n.koreanName.trim().isEmpty()) {
            // e.g. "솔라나(SOL)"
            String symbol = symbolOf(marketCode);
            return n.koreanName + "(" + symbol + ")";
        }
        // fallback: "SOL (KRW-SOL)" 느낌보다 요청은 "솔라나(SOL)"이지만 이름 없으면 심볼만이라도
        String symbol = symbolOf(marketCode);
        return symbol + " (" + marketCode + ")";
    }

    private String symbolOf(String marketCode) {
        int idx = marketCode.indexOf('-');
        return (idx >= 0 && idx < marketCode.length()-1) ? marketCode.substring(idx+1) : marketCode;
    }

    private synchronized void refreshIfNeeded() {
        if (Instant.now().isBefore(loadedAt.plus(TTL)) && !cache.isEmpty()) return;
        try {
            UpbitMarketItem[] arr = restTemplate.getForObject(URL, UpbitMarketItem[].class);
            if (arr == null || arr.length == 0) return;
            Map<String, MarketName> next = new HashMap<String, MarketName>();
            for (UpbitMarketItem it : arr) {
                if (it == null || it.market == null) continue;
                MarketName name = new MarketName(it.korean_name, it.english_name);
                next.put(it.market, name);
            }
            cache.clear();
            cache.putAll(next);
            loadedAt = Instant.now();
        } catch (Exception ignore) {
            // keep old cache
        }
    }

    public static class MarketName {
        public final String koreanName;
        public final String englishName;
        public MarketName(String koreanName, String englishName) {
            this.koreanName = koreanName;
            this.englishName = englishName;
        }
    }

    // Jackson maps snake_case to same field names when declared as-is.
    public static class UpbitMarketItem {
        public String market;
        public String korean_name;
        public String english_name;
    }
}
