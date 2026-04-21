package com.example.upbit.market;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 업비트 현재가(Ticker) API 클라이언트.
 * GET /v1/ticker?markets=KRW-BTC,KRW-SOL (인증 불필요, 배치 요청 가능)
 *
 * 실시간 TP/SL 모니터링에 사용됩니다.
 */
@Service
public class TickerService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TickerService.class);

    private final RestTemplate restTemplate;

    public TickerService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * 여러 마켓의 현재가를 한 번에 조회합니다.
     * @param markets 마켓 코드 리스트 (예: ["KRW-BTC", "KRW-SOL"])
     * @return market -> trade_price 맵. 오류 시 빈 맵 반환.
     */
    public Map<String, Double> getTickerPrices(List<String> markets) {
        if (markets == null || markets.isEmpty()) return Collections.emptyMap();

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < markets.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(markets.get(i));
        }

        String url = "https://api.upbit.com/v1/ticker?markets=" + sb.toString();
        UpbitTicker[] tickers = fetchWithRetry(url);
        if (tickers == null) return Collections.emptyMap();

        Map<String, Double> result = new LinkedHashMap<String, Double>();
        for (UpbitTicker t : tickers) {
            if (t.market != null && t.trade_price > 0) {
                result.put(t.market, t.trade_price);
            }
        }
        return result;
    }

    /**
     * 단일 마켓의 오늘 최고가(high_price)를 조회합니다.
     * 실패 시 0 반환.
     */
    public double getTodayHighPrice(String market) {
        if (market == null || market.isEmpty()) return 0;
        String url = "https://api.upbit.com/v1/ticker?markets=" + market;
        UpbitTicker[] tickers = fetchWithRetry(url);
        if (tickers == null || tickers.length == 0) return 0;
        return Math.max(0, tickers[0].high_price);
    }

    private UpbitTicker[] fetchWithRetry(String url) {
        Exception last = null;
        final int maxRetries = 3;
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            try {
                return restTemplate.getForObject(url, UpbitTicker[].class);
            } catch (HttpClientErrorException e) {
                last = e;
                HttpStatus sc = e.getStatusCode();
                if (sc == HttpStatus.TOO_MANY_REQUESTS || sc.is5xxServerError()) {
                    sleepBackoff(attempt);
                    continue;
                }
                log.warn("[TICKER] HTTP 오류: {} {}", sc, e.getMessage());
                return null;
            } catch (ResourceAccessException e) {
                last = e;
                sleepBackoff(attempt);
            } catch (Exception e) {
                log.warn("[TICKER] 조회 실패: {}", e.getMessage());
                return null;
            }
        }
        log.warn("[TICKER] {}회 재시도 후 실패: {}", maxRetries, last != null ? last.getMessage() : "unknown");
        return null;
    }

    private void sleepBackoff(int attempt) {
        long ms = 500L * (1L << Math.min(attempt, 3));
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
