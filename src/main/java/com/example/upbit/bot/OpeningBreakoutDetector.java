package com.example.upbit.bot;

import com.example.upbit.market.PriceUpdateListener;
import com.example.upbit.market.SharedPriceService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 오프닝 레인지 돌파 실시간 감지기.
 *
 * SharedPriceService의 실시간 가격을 구독하여 rangeHigh 돌파를 즉시 감지.
 * confirmCount 연속 통과 시 OpeningScannerService에 콜백.
 *
 * 흐름:
 * 1. OpeningScannerService가 range 수집 완료 후 setRangeHighMap() 호출
 * 2. start()로 SharedPriceService 리스너 등록
 * 3. 실시간 가격 수신 → 돌파 체크 → confirmCount 통과 시 listener.onBreakoutConfirmed() 호출
 * 4. stop()으로 리스너 해제
 */
@Component
public class OpeningBreakoutDetector {

    private static final Logger log = LoggerFactory.getLogger(OpeningBreakoutDetector.class);

    private final SharedPriceService sharedPriceService;

    // Breakout detection state
    private final ConcurrentHashMap<String, Double> rangeHighMap = new ConcurrentHashMap<String, Double>();
    private final ConcurrentHashMap<String, Double> latestPrices = new ConcurrentHashMap<String, Double>();
    private final ConcurrentHashMap<String, Integer> confirmCounts = new ConcurrentHashMap<String, Integer>();
    private final Set<String> confirmedMarkets = ConcurrentHashMap.newKeySet();

    // Configuration
    private volatile double breakoutPct = 1.0;
    private volatile int requiredConfirm = 3;

    // Callback
    private volatile BreakoutListener listener;
    private volatile PriceUpdateListener priceListener;

    // 실시간 TP/SL 체크용 캐시 (OpeningScannerService에서 업데이트)
    private final ConcurrentHashMap<String, double[]> positionCache = new ConcurrentHashMap<String, double[]>();
    private volatile double cachedTpAtrMult = 1.5;
    private volatile double cachedSlPct = 2.8;
    private volatile double cachedTrailAtrMult = 0.7;

    /** 돌파 확인 시 호출되는 콜백 인터페이스 */
    public interface BreakoutListener {
        void onBreakoutConfirmed(String market, double price, double rangeHigh, double breakoutPctActual);
        default void onTpSlTriggered(String market, double price, String sellType, String reason) {}
    }

    // 실시간 TP 트레일링 상태
    private final ConcurrentHashMap<String, Double> peakPrices = new ConcurrentHashMap<String, Double>();
    private final ConcurrentHashMap<String, Boolean> tpActivated = new ConcurrentHashMap<String, Boolean>();
    private volatile double tpActivatePct = 2.0;
    private volatile double trailFromPeakPct = 1.0;

    public OpeningBreakoutDetector(SharedPriceService sharedPriceService) {
        this.sharedPriceService = sharedPriceService;
    }

    // ========== Configuration ==========

    public void setBreakoutPct(double pct) { this.breakoutPct = pct; }
    public void setRequiredConfirm(int count) { this.requiredConfirm = count; }
    public void setListener(BreakoutListener listener) { this.listener = listener; }
    public void setTpActivatePct(double pct) { this.tpActivatePct = pct; }
    public void setTrailFromPeakPct(double pct) { this.trailFromPeakPct = pct; }

    public void setRangeHighMap(Map<String, Double> map) {
        rangeHighMap.clear();
        rangeHighMap.putAll(map);
        confirmCounts.clear();
        confirmedMarkets.clear();
        latestPrices.clear();
        log.info("[BreakoutDetector] rangeHighMap set: {} markets", map.size());
    }

    public boolean isAlreadyConfirmed(String market) {
        return confirmedMarkets.contains(market);
    }

    public void addPosition(String market, double avgPrice) {
        positionCache.put(market, new double[]{avgPrice});
        peakPrices.put(market, avgPrice);
        tpActivated.put(market, false);
    }

    public void removePosition(String market) {
        positionCache.remove(market);
        peakPrices.remove(market);
        tpActivated.remove(market);
    }

    public void updatePositionCache(Map<String, Double> positions) {
        for (Map.Entry<String, Double> e : positions.entrySet()) {
            if (!positionCache.containsKey(e.getKey())) {
                positionCache.put(e.getKey(), new double[]{e.getValue()});
                peakPrices.put(e.getKey(), e.getValue());
                tpActivated.put(e.getKey(), false);
            }
        }
        for (String market : new ArrayList<String>(positionCache.keySet())) {
            if (!positions.containsKey(market)) {
                removePosition(market);
            }
        }
    }

    public Double getLatestPrice(String market) {
        Double wsPrice = sharedPriceService.getPrice(market);
        return wsPrice != null ? wsPrice : latestPrices.get(market);
    }

    // ========== Lifecycle (SharedPriceService 기반) ==========

    /**
     * 리스너 등록 + 마켓 구독 요청.
     * 이전의 connect()를 대체.
     */
    public void connect(List<String> markets) {
        disconnect();
        if (markets.isEmpty()) return;

        // SharedPriceService에 마켓 구독 요청
        sharedPriceService.ensureMarketsSubscribed(markets);

        // 가격 업데이트 리스너 등록
        priceListener = new PriceUpdateListener() {
            @Override
            public void onPriceUpdate(String market, double price) {
                latestPrices.put(market, price);
                checkBreakout(market, price);
                checkRealtimeTp(market, price);
            }
        };
        sharedPriceService.addGlobalListener(priceListener);

        log.info("[BreakoutDetector] SharedPriceService 리스너 등록: {} markets", markets.size());
    }

    /**
     * 리스너 해제.
     * 이전의 disconnect()를 대체.
     */
    public void disconnect() {
        if (priceListener != null) {
            sharedPriceService.removeGlobalListener(priceListener);
            priceListener = null;
        }
        latestPrices.clear();
        confirmCounts.clear();
    }

    public boolean isConnected() {
        return sharedPriceService.isConnected();
    }

    // ========== Breakout Detection ==========

    private void checkBreakout(String market, double price) {
        if (confirmedMarkets.contains(market)) return;

        Double rangeHigh = rangeHighMap.get(market);
        if (rangeHigh == null || rangeHigh <= 0) return;

        double threshold = rangeHigh * (1.0 + breakoutPct / 100.0);
        boolean breakout = price >= threshold;

        if (breakout) {
            Integer count = confirmCounts.get(market);
            int newCount = (count != null ? count : 0) + 1;
            confirmCounts.put(market, newCount);

            if (newCount >= requiredConfirm) {
                double actualPct = (price - rangeHigh) / rangeHigh * 100.0;
                confirmedMarkets.add(market);
                confirmCounts.remove(market);

                log.info("[BreakoutDetector] BREAKOUT CONFIRMED: {} price={} rangeHigh={} bo=+{}% confirm={}/{}",
                        market, price, rangeHigh, String.format(java.util.Locale.ROOT, "%.2f", actualPct), newCount, requiredConfirm);

                if (listener != null) {
                    try {
                        listener.onBreakoutConfirmed(market, price, rangeHigh, actualPct);
                    } catch (Exception e) {
                        log.error("[BreakoutDetector] listener callback error for {}", market, e);
                    }
                }
            } else {
                log.debug("[BreakoutDetector] {} breakout confirm {}/{}", market, newCount, requiredConfirm);
            }
        } else {
            if (confirmCounts.containsKey(market)) {
                confirmCounts.put(market, 0);
            }
        }
    }

    /**
     * 실시간 TP 트레일링 체크 (DB 접근 없음).
     * +2% 도달 → 트레일링 활성화 → 피크에서 -1% 떨어지면 매도 콜백.
     */
    private void checkRealtimeTp(String market, double price) {
        double[] pos = positionCache.get(market);
        if (pos == null) return;

        double avgPrice = pos[0];
        if (avgPrice <= 0) return;

        double pnlPct = (price - avgPrice) / avgPrice * 100.0;

        // 피크 업데이트
        Double peak = peakPrices.get(market);
        if (peak == null || price > peak) {
            peakPrices.put(market, price);
            peak = price;
        }

        // TP 활성화 체크
        Boolean activated = tpActivated.get(market);
        if (activated == null) activated = false;

        if (!activated && pnlPct >= tpActivatePct) {
            tpActivated.put(market, true);
            activated = true;
            log.info("[BreakoutDetector] TP activated: {} pnl=+{}% peak={} (realtime)",
                    market, String.format(java.util.Locale.ROOT, "%.2f", pnlPct), price);
        }

        // 트레일링 체크 (활성화 후)
        if (activated && peak > avgPrice) {
            double dropFromPeak = (peak - price) / peak * 100.0;
            if (dropFromPeak >= trailFromPeakPct) {
                double trailPnl = (price - avgPrice) / avgPrice * 100.0;
                String reason = String.format(java.util.Locale.ROOT,
                        "TP_TRAIL avg=%.2f peak=%.2f now=%.2f drop=%.2f%% pnl=+%.2f%% (realtime)",
                        avgPrice, peak, price, dropFromPeak, trailPnl);
                log.info("[BreakoutDetector] TP TRAIL triggered: {} | {}", market, reason);

                removePosition(market);

                if (listener != null) {
                    try {
                        listener.onTpSlTriggered(market, price, "TP_TRAIL", reason);
                    } catch (Exception e) {
                        log.error("[BreakoutDetector] TP callback error for {}", market, e);
                    }
                }
            }
        }
    }

    /** 레인지 고점 맵 초기화 (세션 종료 또는 다음 날 준비) */
    public void reset() {
        rangeHighMap.clear();
        confirmCounts.clear();
        confirmedMarkets.clear();
        latestPrices.clear();
        positionCache.clear();
        peakPrices.clear();
        tpActivated.clear();
    }
}
